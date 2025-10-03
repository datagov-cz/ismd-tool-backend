package com.dia.ismdtoolbackend.models;

import lombok.Data;
import lombok.Getter;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.NotBlank;

@Getter
@Data
public class OntologyCreateModel {
    private String namespace;
    @NotBlank
    private NameModel nameModel;
    @NotBlank
    private DescriptionModel descriptionModel;
}
