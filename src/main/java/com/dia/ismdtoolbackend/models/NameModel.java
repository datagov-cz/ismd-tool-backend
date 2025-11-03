package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.enums.LanguageTag;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class NameModel {
    private LanguageTag languageTag;
    private String name;
}
