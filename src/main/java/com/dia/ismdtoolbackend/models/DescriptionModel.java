package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.enums.LanguageTag;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class DescriptionModel {
    private LanguageTag languageTag;
    private String description;
}
