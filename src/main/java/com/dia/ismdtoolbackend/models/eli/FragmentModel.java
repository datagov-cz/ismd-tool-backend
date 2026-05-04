package com.dia.ismdtoolbackend.models.eli;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FragmentModel {
    private String iri;
    private String parentIri;
    private String citation;
    private String kind;
    private String order;
}
