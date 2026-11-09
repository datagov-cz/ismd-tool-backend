package com.dia.ismdtoolbackend.client.validation;

/**
 * The published-corpus (NKD) resource that triggered a global (cross-vocabulary) validation
 * finding. Carried on a {@link ValidationResult} so consumers can navigate to the conflicting
 * NKD entry. {@code null} on local (non-corpus) findings.
 *
 * <p>Depending on the rule, the corpus IRI is either the uploaded subject's own IRI (same-IRI
 * checks) or a different corpus IRI carrying a colliding label (different-IRI-same-name check).
 *
 * @param iri       the corpus resource IRI.
 * @param prefLabel its preferred label (Czech preferred), or {@code null} when the corpus
 *                  publishes no label for it.
 */
public record NkdResource(String iri, String prefLabel) {
}
