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
    /** Rendered HTML body (obsah); null for structural fragments that carry no text. */
    private String bodyHtml;

    /** Structure-only constructor (no body); used by the lean fragment-tree path. */
    public FragmentModel(String iri, String parentIri, String citation, String kind, String order) {
        this(iri, parentIri, citation, kind, order, null);
    }
}
