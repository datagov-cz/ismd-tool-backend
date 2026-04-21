package com.dia.ismdtoolbackend.models.rpp;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RppIsvs {
    private String iri;
    private String code;
    private String nazev;
    private List<String> agendaIris;
}
