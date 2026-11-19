package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.enums.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.RestClientResponseException;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Every error response carries a code and a message, and travels with the code's own status. Each handler
 * is invoked with an exception that has no message, so a handler that bypasses {@link ErrorCode} fails here.
 */
class GlobalExceptionHandlerErrorCodeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Deep stubs for the handlers that read a payload, but no cause chain for the logger to walk. */
    private static final Answer<Object> MESSAGELESS = invocation -> {
        Class<?> returnType = invocation.getMethod().getReturnType();
        if (returnType.isArray()) {
            return Array.newInstance(returnType.getComponentType(), 0);
        }
        if (Throwable.class.isAssignableFrom(returnType) || returnType == String.class) {
            return null;
        }
        if (returnType == HttpStatusCode.class) {
            return HttpStatus.NOT_FOUND;
        }
        return RETURNS_DEEP_STUBS.answer(invocation);
    };

    static Stream<Method> handlers() {
        return Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ExceptionHandler.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    void everyHandlerReturnsACodeAndAMessage(Method method) throws Exception {
        Class<?> exceptionType = method.getParameterTypes()[0];
        Object exception = mock(exceptionType, MESSAGELESS);

        ResponseEntity<?> response = (ResponseEntity<?>) method.invoke(handler, exception);
        ApiResponseDto<?> body = (ApiResponseDto<?>) response.getBody();

        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isFalse();
        assertThat(body.getErrorCode()).as("error code").isNotNull();
        assertThat(body.getMessage()).as("message").isNotBlank();
        // The one handler that relays an upstream status rather than its code's own.
        if (exceptionType != RestClientResponseException.class) {
            assertThat(response.getStatusCode()).isEqualTo(body.getErrorCode().getStatus());
        }
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeIsAnErrorStatusWithAMessage(ErrorCode code) {
        assertThat(code.getStatus().isError()).isTrue();
        assertThat(code.getDefaultMessage()).isNotBlank();
    }
}
