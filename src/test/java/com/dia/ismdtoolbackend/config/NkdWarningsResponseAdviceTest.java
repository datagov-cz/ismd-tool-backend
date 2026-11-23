package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.enums.ApiWarningCode;
import com.dia.ismdtoolbackend.enums.ErrorCode;
import com.dia.ismdtoolbackend.service.snapshot.SkippedNkdChecks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NkdWarningsResponseAdviceTest {

    private static final String IRI = "https://example.org/pojem/a";
    private final NkdWarningsResponseAdvice advice = new NkdWarningsResponseAdvice();

    @BeforeEach
    void request() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private ApiResponseDto<String> write(ApiResponseDto<String> body) {
        advice.beforeBodyWrite(body, null, null, null, null, null);
        return body;
    }

    @Test
    void aSuccessCarriesTheSkippedConcepts() {
        SkippedNkdChecks.record(List.of(IRI));
        ApiResponseDto<String> body = write(ApiResponseDto.success("x", "ok"));
        assertThat(body.getWarnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(ApiWarningCode.NKD_CHECK_SKIPPED);
            assertThat(w.conceptIris()).containsExactly(IRI);
        });
    }

    @Test
    void anErrorCarriesNoWarning() {
        SkippedNkdChecks.record(List.of(IRI));
        ApiResponseDto<String> body = write(ApiResponseDto.error(ErrorCode.values()[0]));
        assertThat(body.getWarnings()).isNull();
    }

    @Test
    void withNothingSkippedTheFieldIsAbsentFromTheJson() throws Exception {
        String json = new ObjectMapper().writeValueAsString(write(ApiResponseDto.success("x", "ok")));
        assertThat(json).doesNotContain("warnings");
    }
}
