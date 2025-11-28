package com.dia.ismdtoolbackend.models;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Data
@Getter
@Setter
public class NameModel {
    private Map<String, String> name;
}