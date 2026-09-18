package com.dia.ismdtoolbackend.models.nkod;

import java.util.List;
import java.util.Map;

/**
 * Full detail of one catalogue dataset, including the concepts it is annotated with.
 *
 * @param iri          dataset IRI
 * @param name         language tag to title
 * @param description  language tag to description
 * @param conceptIris  IRIs from {@code týká-se-pojmu}, deduplicated, in query order.
 * @param distributions the dataset's distributions, in query order; empty when it has none.
 *                      Carries the links to the data.
 */
public record NkodDatasetDetail(String iri,
                                Map<String, String> name,
                                Map<String, String> description,
                                List<String> conceptIris,
                                List<NkodDistribution> distributions) {
}