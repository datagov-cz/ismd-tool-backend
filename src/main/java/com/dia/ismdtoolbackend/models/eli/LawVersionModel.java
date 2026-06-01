package com.dia.ismdtoolbackend.models.eli;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class LawVersionModel {
    private String iri;
    private LocalDate ucinnostOd;
    private LocalDate ucinnostDo;
    private String versionType;
    private boolean latest;
}
