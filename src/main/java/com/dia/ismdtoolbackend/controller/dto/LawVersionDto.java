package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LawVersionDto {
    private String iri;
    private String eliPath;
    private LocalDate ucinnostOd;
    private LocalDate ucinnostDo;
    private String versionType;
    private boolean latest;
}
