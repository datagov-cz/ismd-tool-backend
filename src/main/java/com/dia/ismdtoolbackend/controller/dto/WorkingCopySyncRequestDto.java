package com.dia.ismdtoolbackend.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Which deviating characteristics of a working copy the user wants to take from NKD. Keys are the
 * {@code @JsonProperty} names on {@code PublishedConceptDeviationModel} — the same tokens the FE already
 * received in the deviation block (e.g. {@code "název"}, {@code "definice"}, {@code "definiční-obor"}).
 *
 * <p>The list carries field names only, never values: the service re-derives every accepted value from
 * live NKD, so a client cannot inject one. Accepting <em>every</em> deviating field keeps the concept a
 * working copy; accepting a strict subset severs it to a draft.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkingCopySyncRequestDto {

    /** At least one deviating field key; unknown or non-deviating keys are rejected with 400. */
    @NotEmpty(message = "Musí být vybrána alespoň jedna vlastnost k synchronizaci.")
    private List<String> fieldsToAccept;
}
