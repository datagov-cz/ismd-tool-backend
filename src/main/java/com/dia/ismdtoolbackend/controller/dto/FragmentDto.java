package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FragmentDto {
    private String iri;
    private String eliPath;
    private String kind;
    private String citation;
    private String order;
    private List<FragmentDto> children = new ArrayList<>();
}
