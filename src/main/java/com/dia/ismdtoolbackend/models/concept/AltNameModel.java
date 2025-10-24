package com.dia.ismdtoolbackend.models.concept;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class AltNameModel {
    private String languageTag;
    private String altName;
}
