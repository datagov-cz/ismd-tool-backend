package com.dia.ismdtoolbackend.models.nkod;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A codelist dataset from the NKOD catalogue: a picker entry, and the current catalogue state of a
 * concept's {@code instance-definovány-číselníkem}. Served only with a resolved {@code codeListIri}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NkodCodelist {

    /** {@code dcat:DatasetSeries} or standalone {@code dcat:Dataset} IRI. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String datasetIri;

    /** Codelist IRI of the dataset's current version, read from its distribution file. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String codeListIri;

    /** Czech title. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String title;

    /** Czech publisher name; the publisher IRI when no name is published. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String publisher;

    /** Czech description. */
    private String description;

    /** RPP codelist number, e.g. {@code "151"}; RPP series only. */
    private String codeListNumber;

    /** Start date (ISO) of the series' current version; series only. */
    private String validFrom;
}
