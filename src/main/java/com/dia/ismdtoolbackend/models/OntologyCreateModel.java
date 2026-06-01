package com.dia.ismdtoolbackend.models;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;

@Getter
@Data
public class OntologyCreateModel {
    private String namespace;
    @NotNull
    @Valid
    private NameModel nameModel;
    @NotNull
    @Valid
    private DescriptionModel descriptionModel;
}
