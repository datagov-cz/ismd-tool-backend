package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.OfnConformanceFailureDto;
import com.dia.ismdtoolbackend.enums.ErrorCode;
import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.exception.OfnConformanceException.Context;
import com.dia.ismdtoolbackend.ofn.ConformanceIssue;
import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfnConformanceExceptionHandlerTest {

    private final OfnConformanceExceptionHandler handler = new OfnConformanceExceptionHandler();

    @ParameterizedTest(name = "{0} → {1} ({2})")
    @CsvSource({
            "WRITE, OFN_WRITE_NONCONFORMANT, 500",
            "UPLOAD, UPLOAD_OFN_NONCONFORMANT, 400",
            "DOWNLOAD, ONTOLOGY_DOWNLOAD_BLOCKED_BY_OFN, 400",
    })
    void everyIssueIsListed_underTheContextsErrorCode(Context context, ErrorCode code, int status) {
        ConformanceIssue issue = new ConformanceIssue(Kind.CONCEPT_INCOMPLETE, "https://x/pojem/a", "pojmy[a]",
                "pojem není úplný: chybí povinné pole obor-hodnot", null);

        ResponseEntity<ApiResponseDto<OfnConformanceFailureDto>> response =
                handler.handle(new OfnConformanceException(context, "https://x", List.of(issue)));

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().getErrorCode()).isEqualTo(code);
        OfnConformanceFailureDto body = response.getBody().getData();
        assertThat(body.vocabularyIri()).isEqualTo("https://x");
        assertThat(body.issues()).singleElement().satisfies(dto -> {
            assertThat(dto.kind()).isEqualTo("CONCEPT_INCOMPLETE");
            assertThat(dto.conceptIri()).isEqualTo("https://x/pojem/a");
            assertThat(dto.message()).contains("obor-hodnot");
        });
    }
}
