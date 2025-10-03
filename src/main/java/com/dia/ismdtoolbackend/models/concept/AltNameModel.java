package com.dia.ismdtoolbackend.models.concept;

import lombok.Data;
import lombok.Getter;

@Data
@Getter
public class AltNameModel {
    private String languageTag;
    private String altName;
}
