package com.dia.ismdtoolbackend.entity.models;

import lombok.Data;
import lombok.Getter;

@Getter
@Data
public class OntologyCreateModel {
    private String namespace;
    private String name;
    private String description;
}
