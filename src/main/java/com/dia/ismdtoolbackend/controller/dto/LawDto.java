package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LawDto {
    private String iri;
    private String eliPath;
    private String domain;
    private String citace;
    private String displayName;
    private String cislo;
    private Integer rok;
    private String sbirka;
}
