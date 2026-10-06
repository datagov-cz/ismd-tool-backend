package com.dia.ismdtoolbackend.models.nkod;

import java.util.Map;

/**
 * One harvested catalogue dataset: IRI plus language-keyed titles and descriptions.
 *
 * <p>Held in the in-memory snapshot rather than serialized, so it stays a plain carrier —
 * the wire shape is {@code NkodDatasetListItemDto}.
 *
 * @param iri         dataset IRI; publisher-hosted, so not necessarily under {@code data.gov.cz}
 * @param name        language tag to title
 * @param description language tag to description
 */
public record NkodDatasetRow(String iri, Map<String, String> name, Map<String, String> description) {
}