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
    /** The act's title ({@code "Zákon o civilním letectví"}); null when the source has none (SPARQL). */
    private String nazev;

    public LawModel(String iri, String citace, String cislo, Integer rok, String sbirka) {
        this(iri, citace, cislo, rok, sbirka, null);
    }
}