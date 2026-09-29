package com.dia.ismdtoolbackend.models.nkod;

import java.util.Map;

/**
 * One distribution of a catalogue dataset: the link to offer the user, plus the metadata
 * needed to label it.
 *
 * @param iri         the distribution's own IRI
 * @param name        language tag to title; may be empty
 * @param link        the single link to render — {@code downloadURL} when present, else
 *                    {@code accessURL}. Null only when the publisher supplied neither.
 * @param format      {@code dcterms:format} IRI, or null. The only dependable type signal:
 *                    82% of the URLs carry no file extension, so the link cannot be parsed
 *                    for one.
 * @param mediaType   {@code dcat:mediaType} IRI, or null
 * @param isService   true when the distribution is an API/endpoint rather than a file, i.e.
 *                    it declares {@code dcat:accessService} or offers no {@code downloadURL}.
 *                    Drives "Otevřít" vs "Stáhnout" — every {@code accessService} in the
 *                    catalogue sits in the no-{@code downloadURL} group, and those links are
 *                    WMS/SPARQL/REST endpoints and map viewers, not downloads.
 */
public record NkodDistribution(String iri,
                               Map<String, String> name,
                               String link,
                               String format,
                               String mediaType,
                               boolean isService) {
}