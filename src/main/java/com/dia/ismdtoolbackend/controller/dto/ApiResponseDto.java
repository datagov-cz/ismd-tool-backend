package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ApiResponseDto<T> {
    private T data;
    private String message;
    private boolean success;
    /** Set on every error response, null on success. */
    private ErrorCode errorCode;
    /** What the user should know about a successful write; absent when there is nothing. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ApiWarningDto> warnings;

    public ApiResponseDto(T data, String message, boolean success) {
        this.data = data;
        this.message = message;
        this.success = success;
        this.errorCode = null;
    }

    public ApiResponseDto(String message, boolean success) {
        this.message = message;
        this.success = success;
        this.data = null;
        this.errorCode = null;
    }

    public static <T> ApiResponseDto<T> success(T data, String message) {
        return new ApiResponseDto<>(data, message, true);
    }

    public static <T> ApiResponseDto<T> success(String message) {
        return new ApiResponseDto<>(message, true);
    }

    /** An error carrying the code's own message. */
    public static <T> ApiResponseDto<T> error(ErrorCode errorCode) {
        return error(errorCode, null, null);
    }

    public static <T> ApiResponseDto<T> error(ErrorCode errorCode, String message) {
        return error(errorCode, message, null);
    }

    /** A blank message falls back to the code's own, so an error always carries both. */
    public static <T> ApiResponseDto<T> error(ErrorCode errorCode, String message, T data) {
        String text = message == null || message.isBlank() ? errorCode.getDefaultMessage() : message;
        return new ApiResponseDto<>(data, text, false, errorCode, null);
    }
}
