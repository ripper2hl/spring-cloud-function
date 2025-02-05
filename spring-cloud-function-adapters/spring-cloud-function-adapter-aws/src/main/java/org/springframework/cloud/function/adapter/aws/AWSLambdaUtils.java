/*
 * Copyright 2021-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.adapter.aws;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.serialization.PojoSerializer;
import com.amazonaws.services.lambda.runtime.serialization.events.LambdaEventSerializers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

/**
 *
 * @author Oleg Zhurakousky
 *
 */
final class AWSLambdaUtils {

	private static Log logger = LogFactory.getLog(AWSLambdaUtils.class);

	private static final String AWS_API_GATEWAY = "aws-api-gateway";

	private AWSLambdaUtils() {

	}

	public static Message<byte[]> generateMessage(byte[] payload, MessageHeaders headers,
			Type inputType, ObjectMapper objectMapper) {
		return generateMessage(payload, headers, inputType, objectMapper, null);
	}

	private static boolean isSupportedAWSType(Type inputType) {
		Class<?> inputClass = FunctionTypeUtils.getRawType(inputType);
		return APIGatewayV2HTTPEvent.class.isAssignableFrom(inputClass)
				|| S3Event.class.isAssignableFrom(inputClass)
				|| APIGatewayProxyRequestEvent.class.isAssignableFrom(inputClass)
				|| SNSEvent.class.isAssignableFrom(inputClass)
				|| SQSEvent.class.isAssignableFrom(inputClass)
				|| KinesisEvent.class.isAssignableFrom(inputClass);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Message<byte[]> generateMessage(byte[] payload, MessageHeaders headers,
			Type inputType, ObjectMapper objectMapper, @Nullable Context awsContext) {

		if (logger.isInfoEnabled()) {
			logger.info("Incoming JSON Event: " + new String(payload));
		}

		if (FunctionTypeUtils.isMessage(inputType)) {
			inputType = FunctionTypeUtils.getImmediateGenericType(inputType, 0);
		}

		MessageBuilder messageBuilder = null;
		if (inputType != null && isSupportedAWSType(inputType)) {
			PojoSerializer<?> serializer = LambdaEventSerializers.serializerFor(FunctionTypeUtils.getRawType(inputType), Thread.currentThread().getContextClassLoader());
			Object event = serializer.fromJson(new ByteArrayInputStream(payload));
			messageBuilder = MessageBuilder.withPayload(event);
			if (event instanceof APIGatewayProxyRequestEvent || event instanceof APIGatewayV2HTTPEvent) {
				messageBuilder.setHeader(AWS_API_GATEWAY, true);
				logger.info("Incoming request is API Gateway");
			}
		}
		else {
			if (!objectMapper.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)) {
				configureObjectMapper(objectMapper);
			}
			Object request;
			try {
				request = objectMapper.readValue(payload, Object.class);
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}

			if (request instanceof Map) {
				if (((Map) request).containsKey("httpMethod")) { //API Gateway
					boolean mapInputType = (inputType instanceof ParameterizedType && ((Class<?>) ((ParameterizedType) inputType).getRawType()).isAssignableFrom(Map.class));
					if (mapInputType) {
						messageBuilder = MessageBuilder.withPayload(request).setHeader("httpMethod", ((Map) request).get("httpMethod"));
					}
					else {
						Object body = ((Map) request).remove("body");
						try {
							body = body instanceof String
									? String.valueOf(body).getBytes(StandardCharsets.UTF_8)
											: objectMapper.writeValueAsBytes(body);
						}
						catch (Exception e) {
							throw new IllegalStateException(e);
						}

						messageBuilder = MessageBuilder.withPayload(body).copyHeaders(((Map) request));
					}
					messageBuilder.setHeader(AWS_API_GATEWAY, true);
				}
				Object providedHeaders = ((Map) request).remove("headers");
				if (providedHeaders != null && providedHeaders instanceof Map) {
					messageBuilder.removeHeader("headers");
					messageBuilder.copyHeaders((Map<String, Object>) providedHeaders);
				}
			}
			else if (request instanceof Iterable) {
				messageBuilder = MessageBuilder.withPayload(request);
			}
		}


		if (messageBuilder == null) {
			messageBuilder = MessageBuilder.withPayload(payload);
		}
		if (awsContext != null) {
			messageBuilder.setHeader("aws-context", awsContext);
		}
		logger.info("Incoming request headers: " + headers);

		return messageBuilder.copyHeaders(headers).build();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static byte[] generateOutput(Message requestMessage, Message<byte[]> responseMessage,
			ObjectMapper objectMapper, Type functionOutputType) {

		Class<?> outputClass = FunctionTypeUtils.getRawType(functionOutputType);
		if (outputClass != null && (APIGatewayV2HTTPResponse.class.isAssignableFrom(outputClass)
				|| APIGatewayProxyResponseEvent.class.isAssignableFrom(outputClass))) {
			return responseMessage.getPayload();
		}


		if (!objectMapper.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)) {
			configureObjectMapper(objectMapper);
		}
		byte[] responseBytes = responseMessage  == null ? "\"OK\"".getBytes() : responseMessage.getPayload();
		if (requestMessage.getHeaders().containsKey(AWS_API_GATEWAY) && ((boolean) requestMessage.getHeaders().get(AWS_API_GATEWAY))) {
			Map<String, Object> response = new HashMap<String, Object>();
			response.put("isBase64Encoded", false);

			AtomicReference<MessageHeaders> headers = new AtomicReference<>();
			int statusCode = HttpStatus.OK.value();
			if (responseMessage != null) {
				headers.set(responseMessage.getHeaders());
				statusCode = headers.get().containsKey("statusCode")
						? (int) headers.get().get("statusCode")
						: HttpStatus.OK.value();
			}

			response.put("statusCode", statusCode);
			if (isRequestKinesis(requestMessage)) {
				HttpStatus httpStatus = HttpStatus.valueOf(statusCode);
				response.put("statusDescription", httpStatus.toString());
			}

			String body = responseMessage == null
					? "\"OK\"" : new String(responseMessage.getPayload(), StandardCharsets.UTF_8).replaceAll("\\\"", "");
			response.put("body", body);

			if (responseMessage != null) {
				Map<String, String> responseHeaders = new HashMap<>();
				headers.get().keySet().forEach(key -> responseHeaders.put(key, headers.get().get(key).toString()));
				response.put("headers", responseHeaders);
			}

			try {
				responseBytes = objectMapper.writeValueAsBytes(response);
			}
			catch (Exception e) {
				throw new IllegalStateException("Failed to serialize AWS Lambda output", e);
			}
		}
		return responseBytes;
	}

	private static void configureObjectMapper(ObjectMapper objectMapper) {
		SimpleModule module = new SimpleModule();
		module.addDeserializer(Date.class, new JsonDeserializer<Date>() {
			@Override
			public Date deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
					throws IOException {
				Calendar calendar = Calendar.getInstance();
				calendar.setTimeInMillis(jsonParser.getValueAsLong());
				return calendar.getTime();
			}
		});
		objectMapper.registerModule(module);
		objectMapper.registerModule(new JodaModule());
		objectMapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
	}


	private static boolean isRequestKinesis(Message<Object> requestMessage) {
		return requestMessage.getHeaders().containsKey("Records");
	}
}
