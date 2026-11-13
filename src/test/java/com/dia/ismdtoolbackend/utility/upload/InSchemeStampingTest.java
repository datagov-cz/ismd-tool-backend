package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.exception.InSchemeDecisionRequiredException;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InSchemeStampingTest {

    private static final String V = Ttl.VOCABULARY.substring(0, Ttl.VOCABULARY.length() - 1);

    private static Model model() {
        return Ttl.parse("""
                pojem:a a skos:Concept, slovníky:pojem .
                pojem:b a slovníky:pojem .
                pojem:c a skos:Concept ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> .
                <https://slovník.gov.cz/jiný/pojem/x> a skos:Concept .
                <https://slovník.gov.cz/datový/test-slovník-2/pojem/y> a skos:Concept .
                [ a skos:Concept ] .
                """);
    }

    @Test
    void missing_listsOwnedConceptsWithoutInScheme_withSlashBoundary_withoutChangingTheModel() {
        Model model = model();
        long size = model.size();

        assertThat(InSchemeStamping.missing(model, V)).containsExactlyInAnyOrder(Ttl.POJEM + "a", Ttl.POJEM + "b");
        assertThat(model.size()).isEqualTo(size);
    }

    @Test
    void noDecision_withMissingConcepts_asksTheUser() {
        assertThatThrownBy(() -> InSchemeStamping.chosen(model(), V, null, null))
                .isInstanceOf(InSchemeDecisionRequiredException.class);
    }

    @Test
    void decisions_mapToTheChosenConcepts() {
        Model model = model();

        assertThat(InSchemeStamping.chosen(model, V, NormalizeMode.NORMALIZE_ALL, null))
                .containsExactlyInAnyOrder(Ttl.POJEM + "a", Ttl.POJEM + "b");
        assertThat(InSchemeStamping.chosen(model, V, NormalizeMode.EXCLUDE_ALL, null)).isEmpty();
        assertThat(InSchemeStamping.chosen(model, V, NormalizeMode.PER_CONCEPT,
                List.of(Ttl.POJEM + "a", "https://example.org/unknown"))).containsExactly(Ttl.POJEM + "a");
        assertThat(InSchemeStamping.chosen(model, V, NormalizeMode.PER_CONCEPT, null)).isEmpty();
    }

    @Test
    void nothingMissing_needsNoDecision() {
        Model model = Ttl.parse("pojem:a a skos:Concept ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> .");

        assertThat(InSchemeStamping.chosen(model, V, null, null)).isEmpty();
    }

    @Test
    void stamp_addsInSchemeOnlyToChosenConcepts() {
        Model model = model();

        int stamped = InSchemeStamping.stamp(model, V, Set.of(Ttl.POJEM + "a", "https://slovník.gov.cz/jiný/pojem/x"));

        assertThat(stamped).isEqualTo(1);
        assertThat(model.contains(model.createResource(Ttl.POJEM + "a"), SKOS.inScheme, model.createResource(V))).isTrue();
        assertThat(model.createResource(Ttl.POJEM + "b").hasProperty(SKOS.inScheme)).isFalse();
        assertThat(model.createResource("https://slovník.gov.cz/jiný/pojem/x").hasProperty(SKOS.inScheme)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void missingGraphName_isRejected(String graphName) {
        assertThatThrownBy(() -> InSchemeStamping.missing(model(), graphName)).isInstanceOf(IllegalArgumentException.class);
    }
}
