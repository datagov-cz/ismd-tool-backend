package com.dia.ismdtoolbackend.models;

import lombok.Data;
import lombok.Getter;

@Getter
@Data
public class OntologyEditModel {
    private String ontologyIRI;
    private NameModel nameModel;
    private DescriptionModel descriptionModel;
}
