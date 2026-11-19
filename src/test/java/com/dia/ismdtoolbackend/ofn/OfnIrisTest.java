package com.dia.ismdtoolbackend.ofn;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link OfnIris} constant is a namespace the bundled context defines as a prefix, or the IRI of one of
 * its terms, so a renamed or removed OFN term fails the build instead of producing data the gate drops.
 */
class OfnIrisTest {

    @Test
    void everyConstantIsAContextPrefixOrTermIri() throws IllegalAccessException {
        OfnContextIndex context = OfnSpecification.bundled().contextIndex();
        Set<String> known = new HashSet<>(context.prefixes().values());
        context.allTerms().values().forEach(term -> {
            if (term.id() != null && !term.id().startsWith("@")) {
                known.add(context.expand(term.id()));
            }
        });

        List<String> constants = OfnTermsTest.constants(OfnIris.class);

        assertThat(constants).isNotEmpty();
        assertThat(constants).as("OfnIris constants that are neither a prefix nor a term of kontext.jsonld")
                .allMatch(known::contains);
    }

    @Test
    void theOwlKindsAreTheThreeConceptKinds() {
        assertThat(OfnIris.OWL_KINDS).containsExactlyInAnyOrder(
                OfnIris.OWL_CLASS, OfnIris.OWL_DATATYPE_PROPERTY, OfnIris.OWL_OBJECT_PROPERTY);
    }
}
