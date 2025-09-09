package com.dia.ismdtoolbackend.entity.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Data
public class OntologyMetadataDto {
    private String id;
    private String graphName;
    private String userId;
}
