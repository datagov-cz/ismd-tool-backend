package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.models.NameModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record OntologyIriCheckRequestDto(String namespace, @NotNull @Valid NameModel nameModel) {
}
