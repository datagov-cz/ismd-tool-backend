package com.dia.ismdtoolbackend.models.eli;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LawModel {
    private String iri;
    private String citace;
    private String cislo;
    private Integer rok;
    private String sbirka;
}
