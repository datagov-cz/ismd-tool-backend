package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.ofn.OfnIris;

/**
 * A concept IRI must lie in its vocabulary's namespace ({@code <vocabulary>/…}). The OFN write gate
 * refuses such a concept too; this rule turns the user's input error into a 400 before the gate.
 */
public final class ConceptNamespaceRule {

    private ConceptNamespaceRule() {
    }

    /** @throws ConceptValidationException when the concept IRI is outside the vocabulary's namespace */
    public static void requireInVocabulary(String conceptIri, String vocabularyIri) {
        if (!OfnIris.isOwnedBy(conceptIri, vocabularyIri)) {
            throw new ConceptValidationException("IRI pojmu " + conceptIri
                    + " neleží ve jmenném prostoru slovníku " + vocabularyIri + "/.");
        }
    }
}
