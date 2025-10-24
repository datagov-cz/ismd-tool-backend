package com.dia.ismdtoolbackend.models.concept;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class DefinitionModel {
    private String languageTag;
    private String definition;
}
