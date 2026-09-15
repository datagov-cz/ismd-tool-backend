package com.dia.ismdtoolbackend.models.nkod;

import java.util.List;
import java.util.Map;

/**
 * Full detail of one catalogue dataset, including the concepts it is annotated with.
 *
 * @param iri          dataset IRI
 * @param name         language tag to title
 * @param description  language tag to description
 * @param landingPage  {@code dcat:landingPage}, or null — the "open in NKD" target
 * @param conceptIris  IRIs from {@code týká-se-pojmu}, deduplicated, in query order.
 */
public record NkodDatasetDetail(String iri,
                                Map<String, String> name,
                                Map<String, String> description,
                                String landingPage,
                                List<String> conceptIris) {
}