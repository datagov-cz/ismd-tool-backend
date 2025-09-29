package com.dia.ismdtoolbackend.entity.models;

import lombok.Data;
import lombok.Getter;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.NotBlank;

@Getter
@Data
public class OntologyCreateModel {
    private String namespace;
    @NotBlank
    private String name;
    @NotBlank
    private String description;
}
