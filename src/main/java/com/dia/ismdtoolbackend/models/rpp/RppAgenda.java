package com.dia.ismdtoolbackend.models.rpp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RppAgenda {
    private String iri;
    private String code;
    private String nazev;
}
