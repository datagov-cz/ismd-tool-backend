package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.NkodCodelistStatus;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A concept's stored codelist checked against the NKOD codelist snapshot. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NkodCodelistCheckDto {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private NkodCodelistStatus status;

    /** The dataset as currently published; absent when {@code status} is {@code MISSING}. */
    @Valid
    private NkodCodelist codelist;
}
