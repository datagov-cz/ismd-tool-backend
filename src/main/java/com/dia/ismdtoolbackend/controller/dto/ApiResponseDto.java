package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ApiResponseDto<T> {
    private T data;
    private String message;
    private boolean success;
    /**
     * Stable, machine-readable error code for the FE to branch on (e.g.
     * {@code MISSING_INSCHEME_DECISION_REQUIRED}). Null for success responses and for
     * errors that don't need a distinct code.
     */
    private String errorCode;

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

    public static <T> ApiResponseDto<T> error(String message) {
        return new ApiResponseDto<>(message, false);
    }

    public static <T> ApiResponseDto<T> error(T data, String message, String errorCode) {
        return new ApiResponseDto<>(data, message, false, errorCode);
    }
}