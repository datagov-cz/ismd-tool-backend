package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ApiWarningDto;
import com.dia.ismdtoolbackend.enums.ApiWarningCode;
import com.dia.ismdtoolbackend.service.snapshot.SkippedNkdChecks;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;

/** Adds the request's skipped NKD checks to a successful response. */
@RestControllerAdvice
public class NkdWarningsResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(@NonNull MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public @Nullable Object beforeBodyWrite(@Nullable Object body, @NonNull MethodParameter returnType,
                                            @NonNull MediaType contentType,
                                            @NonNull Class<? extends HttpMessageConverter<?>> converterType,
                                            @NonNull ServerHttpRequest request,
                                            @NonNull ServerHttpResponse response) {
        if (body instanceof ApiResponseDto<?> envelope && envelope.isSuccess()) {
            List<String> skipped = SkippedNkdChecks.current();
            if (!skipped.isEmpty()) {
                envelope.setWarnings(List.of(new ApiWarningDto(ApiWarningCode.NKD_CHECK_SKIPPED, skipped)));
            }
        }
        return body;
    }
}
