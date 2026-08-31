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
    /**
     * Whether this node is a navigable step in the law's structure. False for unnumbered text
     * blocks ({@code frag_*} with no children), which carry body text but no citation and so
     * would render as a blank navigation entry. Such nodes still belong in the rendered body —
     * consumers building navigation skip them, consumers rendering text do not.
     */
    private boolean navigable = true;
    private List<FragmentDto> children = new ArrayList<>();
}
