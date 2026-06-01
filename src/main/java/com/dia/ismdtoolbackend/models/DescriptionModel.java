package com.dia.ismdtoolbackend.models;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Data
@Getter
@Setter
public class DescriptionModel {
    private Map<String, String> description;
}
