package com.dia.ismdtoolbackend.models.concept;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DigitalObjectModel {
    private String name;
    private String description;
    private String url;
}
