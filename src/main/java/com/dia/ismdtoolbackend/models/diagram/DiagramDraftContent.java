package com.dia.ismdtoolbackend.models.diagram;

import com.dia.ismdtoolbackend.enums.ConceptType;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * The concept-shaped content of a draft node — the <em>only</em> concept content the diagram owns.
 *
 * <p>Deliberately minimal and tentative: enough to sketch a concept on the canvas before it exists, not a
 * full concept model. Full validation happens at promote time, when this is mapped to a real
 * {@code ConceptCreateModel} and run through the existing create path. References here (domain, range,
 * broader) point at other node ids on the canvas — an {@code iri:…} for a real concept or a
 * {@code draft:…} for another draft — resolved to real IRIs only once every endpoint is promoted.
 *
 * <p>Serialized to the {@code draft_json} text column of {@code DiagramNodeEntity}.
 */
@Data
public class DiagramDraftContent {

    /** Which kind of concept this draft is intended to become. */
    private ConceptType conceptType;

    /** Tentative label, lang → value (e.g. {@code {"cs": "Zaměstnanec"}}). */
    private Map<String, String> label;

    /** Tentative definition, lang → value. */
    private Map<String, String> definition;

    /** Tentative description, lang → value. */
    private Map<String, String> description;

    /** For a draft VZTAH: the intended domain node id ({@code iri:…} or {@code draft:…}). */
    private String domain;

    /** For a draft VZTAH: the intended range node id. For a draft VLASTNOST: its value range. */
    private String range;

    /** Intended super-concepts (subclass/sub-property/super-relation), by node id. */
    private List<String> broader;
}