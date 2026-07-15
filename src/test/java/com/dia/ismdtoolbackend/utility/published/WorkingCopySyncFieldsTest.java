package com.dia.ismdtoolbackend.utility.published;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.PropertyDeviation;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase C: the working-copy sync field vocabulary — what deviates, what may be accepted, how it applies. */
class WorkingCopySyncFieldsTest {

    private final WorkingCopySyncFields fields = new WorkingCopySyncFields();

    private static <T> PropertyDeviation<T> deviating(T local, T published) {
        return PropertyDeviation.<T>builder().localValue(local).publishedValue(published).isDifferent(true).build();
    }

    @Test
    void deviatingKeys_onlyReportsFieldsThatDeviate() {
        // The comparator only populates fields that differ — a null PropertyDeviation means "same".
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .name(deviating(Map.of("cs", "Místní"), Map.of("cs", "Publikovaný")))
                .identifier(deviating("mistni", "publikovany"))
                .build();

        assertEquals(java.util.Set.of("název", "identifikátor"), fields.deviatingSyncableKeys(deviation));
    }

    @Test
    void deviatingKeys_typeNeverOffered() {
        // ISMD cannot convert between concept types, so `typ` is never syncable even when NKD deviates.
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .types(deviating(List.of("Třída"), List.of("Vlastnost")))
                .name(deviating(Map.of("cs", "A"), Map.of("cs", "B")))
                .build();

        assertEquals(java.util.Set.of("název"), fields.deviatingSyncableKeys(deviation));
        assertFalse(fields.syncableKeys().contains(WorkingCopySyncFields.TYPE_KEY));
    }

    @Test
    void deviatingKeys_altNameNeverOffered() {
        // Alt names are lossy to map (Map<String,Object> → one string per language), so they are not
        // acceptable — accepting would silently drop every label after the first.
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .alternativeName(deviating(Map.of("cs", "A"), Map.of("cs", "B")))
                .build();

        assertTrue(fields.deviatingSyncableKeys(deviation).isEmpty());
        assertFalse(fields.syncableKeys().contains(WorkingCopySyncFields.ALT_NAME_KEY));
    }

    @Test
    void deviatingKeys_nullDeviation_isEmpty() {
        assertTrue(fields.deviatingSyncableKeys(null).isEmpty());
    }

    @Test
    void apply_takesTheNkdValue_notTheLocalOne() {
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .name(Map.of("cs", "Publikovaný název"))
                .identifier("publikovany-identifikator")
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("název", edit, nkd);
        fields.apply("identifikátor", edit, nkd);

        assertEquals(Map.of("cs", "Publikovaný název"), edit.getNameModel().getName());
        assertEquals("publikovany-identifikator", edit.getIdentifier());
    }

    @Test
    void apply_onlyTouchesTheAcceptedField() {
        // The core of the design: everything not accepted stays null, so the edit path's null
        // early-returns leave it untouched.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .name(Map.of("cs", "Nový"))
                .identifier("novy")
                .definition(Map.of("cs", "Nová definice"))
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("název", edit, nkd);

        assertEquals(Map.of("cs", "Nový"), edit.getNameModel().getName());
        assertNull(edit.getIdentifier(), "unaccepted field must stay null");
        assertNull(edit.getDefinitionModel(), "unaccepted field must stay null");
    }

    @Test
    void apply_rangeMapsToDataTypeForProperty_butRangeForRelationship() {
        // A VLASTNOST's range is an XSD datatype (dataType on the edit model); a VZTAH's is a concept IRI.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .range("http://www.w3.org/2001/XMLSchema#string")
                .build();

        PropertyConceptEditModel property = new PropertyConceptEditModel();
        fields.apply("obor-hodnot", property, nkd);
        assertEquals("http://www.w3.org/2001/XMLSchema#string", property.getDataType());
        assertNull(property.getDomain());

        RelationshipConceptEditModel relationship = new RelationshipConceptEditModel();
        fields.apply("obor-hodnot", relationship, nkd);
        assertEquals("http://www.w3.org/2001/XMLSchema#string", relationship.getRange());
    }

    @Test
    void apply_hierarchyKeyOnWrongType_isNoOpNotCrash() {
        // "nadřazená-třída" belongs to TRIDA; on a VLASTNOST it must no-op rather than ClassCastException.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .broaderClasses(List.of("https://slovník.gov.cz/agendový/104/pojem/nadrazena"))
                .build();
        PropertyConceptEditModel edit = new PropertyConceptEditModel();

        fields.apply("nadřazená-třída", edit, nkd);

        assertNull(edit.getSuperProperty());
    }

    @Test
    void apply_unknownKey_isNoOp() {
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("neexistující-vlastnost", edit, ConceptDetailModel.builder().build());

        assertNull(edit.getNameModel());
    }
}
