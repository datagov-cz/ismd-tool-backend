package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.NkodResolutionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The codelist IRI of one NKOD codelist dataset. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NkodCodelistResolutionDto {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String datasetIri;

    /** Codelist IRI; null unless {@code status} is {@code RESOLVED}. */
    private String codeListIri;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private NkodResolutionStatus status;
}
