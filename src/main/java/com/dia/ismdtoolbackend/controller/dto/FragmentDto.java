package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FragmentDto {
    private String iri;
    private String eliPath;
    private String kind;
    private String citation;
    private String order;
    /**
     * Rendered HTML body (obsah) of this fragment. Populated only by the whole-version
     * content endpoint ({@code /api/eli/law/content}); null on the lean fragment-tree
     * endpoint ({@code /api/eli/law/fragments}) and for structural fragments with no text.
     */
    private String bodyHtml;
    private List<FragmentDto> children = new ArrayList<>();
}
