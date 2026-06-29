package com.dia.ismdtoolbackend.models.rpp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RppIsvs {
    private String iri;
    private String code;
    private String nazev;
    private List<String> agendaIris;
}
