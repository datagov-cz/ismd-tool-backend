package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.enums.ErrorCode;
import org.springframework.http.ResponseEntity;

/**
 * Error responses for {@link GlobalExceptionHandler} and the message policy behind them. What reaches the
 * client is the code's default message unless the message is known to be written for the client: a 5xx
 * never carries the exception's text, and exception types that libraries also throw carry it only when this
 * application threw them.
 */
final class ErrorResponses {

    private static final String APP_PACKAGE = "com.dia.";

    private ErrorResponses() {
    }

    static ResponseEntity<ApiResponseDto<Void>> error(ErrorCode code) {
        return error(code, null, null);
    }

    static ResponseEntity<ApiResponseDto<Void>> error(ErrorCode code, String message) {
        return error(code, message, null);
    }

    /**
     * The status comes from the code, so the two cannot disagree. A 5xx drops {@code message}: a server-side
     * failure's text describes internals (SQL, store responses, wrapped causes) the client cannot act on.
     */
    static <T> ResponseEntity<ApiResponseDto<T>> error(ErrorCode code, String message, T data) {
        String clientMessage = code.getStatus().is5xxServerError() ? null : message;
        return ResponseEntity.status(code.getStatus()).body(ApiResponseDto.error(code, clientMessage, data));
    }

    /** A message the handler composed itself, kept on any status. */
    static ResponseEntity<ApiResponseDto<Void>> craftedError(ErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(ApiResponseDto.error(code, message, null));
    }

    /**
     * The exception's message when this application threw it, else null. A library's message for the same
     * type (Hibernate's {@code EntityNotFoundException}, a JDK {@code IllegalArgumentException}) can name
     * internal classes and values.
     */
    static String ownMessage(Throwable e) {
        StackTraceElement[] trace = e.getStackTrace();
        return trace.length > 0 && trace[0].getClassName().startsWith(APP_PACKAGE) ? e.getMessage() : null;
    }
}
