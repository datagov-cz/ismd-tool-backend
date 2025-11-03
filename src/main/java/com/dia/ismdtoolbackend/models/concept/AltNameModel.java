package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.LanguageTag;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class AltNameModel {
    private LanguageTag languageTag;
    private String altName;
}
