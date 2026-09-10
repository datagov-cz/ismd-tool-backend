package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConceptMetadataFactoryTest {
    @Test
    void slugSuffixesAccountForDatabaseAndUnpersistedBatchReservations() {
        var ontology = new OntologyMetadataEntity();
        ontology.setGraphName("https://example.org/doprava");
        ontology.setSlug("old-slug-before-rename");
        var concept = new ClassConceptModel();
        concept.setConceptType("třída");
        concept.setInTezaurus(true);
        var names = new NameModel();
        names.setName(Map.of("en", "Vehicle", "cs", "Vozidlo"));
        concept.setNameModel(names);
        Set<String> reserved = new HashSet<>();
        Set<String> database = Set.of("doprava-auto", "doprava-auto-2");

        var first = ConceptMetadataFactory.create(concept, "https://one.example/auto", "creator", ontology, database::contains, reserved);
        var second = ConceptMetadataFactory.create(concept, "https://two.example/auto", "creator", ontology, database::contains, reserved);
        assertThat(first.getSlug()).isEqualTo("doprava-auto-1");
        assertThat(second.getSlug()).isEqualTo("doprava-auto-3");
        assertThat(first.getConceptName()).isEqualTo("Vozidlo");
        assertThat(first.getConceptType()).isEqualTo(concept.getConceptTypeEnum());
        assertThat(first.getConceptIri()).isEqualTo("https://one.example/auto");
        assertThat(first.getGraphName()).isEqualTo(ontology.getGraphName());
        assertThat(first.getUserId()).isEqualTo("creator");
        assertThat(first.getIsPublished()).isFalse();
        assertThat(first.getInTezaurus()).isTrue();
        assertThat(first.getOntologyMetadata()).isSameAs(ontology);
    }

    @Test
    void preservesCzechPreferenceAndOrdinaryCreateFallback() {
        assertThat(ConceptMetadataFactory.nameForMetadata(null)).isEmpty();
        var name = new NameModel();
        assertThat(ConceptMetadataFactory.nameForMetadata(name)).isEmpty();
        name.setName(Map.of("en", "Vehicle"));
        assertThat(ConceptMetadataFactory.nameForMetadata(name)).isEqualTo("Vehicle");
        name.setName(Map.of("cs", "Vozidlo", "en", "Vehicle"));
        assertThat(ConceptMetadataFactory.nameForMetadata(name)).isEqualTo("Vozidlo");
    }
}
