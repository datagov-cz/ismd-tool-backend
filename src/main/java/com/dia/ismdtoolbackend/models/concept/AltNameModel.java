package com.dia.ismdtoolbackend.models.concept;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Data
@Getter
@Setter
public class AltNameModel {
    private Map<String, String> altName;

}
