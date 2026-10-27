package com.dia.ismdtoolbackend.utility.published;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.PropertyDeviation;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanWrapperImpl;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.dia.constants.VocabularyConstants.NEVEREJNY_UDAJ_JSON_LD;
import static com.dia.constants.VocabularyConstants.TOP_JSON_LD;
import static com.dia.constants.VocabularyConstants.TSP_JSON_LD;
import static com.dia.constants.VocabularyConstants.VEREJNY_UDAJ_JSON_LD;
import static org.assertj.core.api.Assertions.assertThat;

/** The working-copy sync field vocabulary: what deviates, what may be accepted, how it applies. */
class WorkingCopySyncFieldsTest {

    private final WorkingCopySyncFields fields = new WorkingCopySyncFields();

    private static <T> PropertyDeviation<T> deviating(T local, T published) {
        return PropertyDeviation.<T>builder().localValue(local).publishedValue(published).isDifferent(true).build();
    }

    @Test
    void deviatingKeys_onlyReportsFieldsThatDeviate() {
        // The comparator only populates fields that differ — a null PropertyDeviation means "same".
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .definition(deviating(Map.of("cs", "Místní"), Map.of("cs", "Publikovaný")))
                .identifier(deviating("mistni", "publikovany"))
                .build();

        assertThat(fields.deviatingSyncableKeys(deviation)).isEqualTo(java.util.Set.of("definice", "identifikátor"));
    }

    @Test
    void deviatingKeys_typeNeverOffered() {
        // ISMD cannot convert between concept types, so `typ` is never syncable even when NKD deviates.
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .types(deviating(List.of("Třída"), List.of("Vlastnost")))
                .definition(deviating(Map.of("cs", "A"), Map.of("cs", "B")))
                .build();

        assertThat(fields.deviatingSyncableKeys(deviation)).isEqualTo(java.util.Set.of("definice"));
        assertThat(fields.syncableKeys()).doesNotContain(WorkingCopySyncFields.TYPE_KEY);
    }

    @Test
    void nameIsNeverSyncable() {
        // An OFN concept's IRI is derived from its name; syncing the name would rewrite the IRI and break
        // the twin match, so "název" is not in the vocabulary and is never offered even if set.
        assertThat(fields.syncableKeys()).doesNotContain("název");

        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .name(deviating(Map.of("cs", "Místní"), Map.of("cs", "Publikovaný")))
                .build();
        assertThat(fields.deviatingSyncableKeys(deviation)).as("a name deviation offers no syncable key").isEmpty();
    }

    @Test
    void deviatingKeys_altNameIsOffered() {
        // AltNameModel now holds a list per language, matching the deviation's Map<String,Object>,
        // so alt names are syncable.
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .alternativeName(deviating(Map.of("cs", "A"), Map.of("cs", "B")))
                .build();

        assertThat(fields.deviatingSyncableKeys(deviation))
                .isEqualTo(java.util.Set.of(WorkingCopySyncFields.ALT_NAME_KEY));
        assertThat(fields.syncableKeys()).contains(WorkingCopySyncFields.ALT_NAME_KEY);
    }

    @Test
    void apply_altName_keepsEveryLabelPerLanguage() {
        // The reason this field was excluded: a language holding several alt labels must not lose any.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .alternativeName(Map.of("cs", List.of("Obec", "Municipalita"), "en", List.of("Municipality")))
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply(WorkingCopySyncFields.ALT_NAME_KEY, edit, nkd);

        assertThat(edit.getAltNameModel().getAltName().get("cs")).isEqualTo(List.of("Obec", "Municipalita"));
        assertThat(edit.getAltNameModel().getAltName().get("en")).isEqualTo(List.of("Municipality"));
    }

    @Test
    void apply_altName_acceptsSingleStringFromNkd() {
        // NKD's Map<String,Object> carries a bare string when a language has exactly one label.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .alternativeName(Map.of("cs", "Obec"))
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply(WorkingCopySyncFields.ALT_NAME_KEY, edit, nkd);

        assertThat(edit.getAltNameModel().getAltName().get("cs")).isEqualTo(List.of("Obec"));
    }

    @Test
    void apply_altName_nullFromNkd_yieldsEmptyMapNotCrash() {
        ConceptDetailModel nkd = ConceptDetailModel.builder().build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply(WorkingCopySyncFields.ALT_NAME_KEY, edit, nkd);

        assertThat(edit.getAltNameModel().getAltName() == null
                || edit.getAltNameModel().getAltName().isEmpty())
                .isTrue();
    }

    @Test
    void deviatingKeys_nullDeviation_isEmpty() {
        assertThat(fields.deviatingSyncableKeys(null)).isEmpty();
    }

    @Test
    void apply_takesTheNkdValue_notTheLocalOne() {
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .definition(Map.of("cs", "Publikovaná definice"))
                .identifier("publikovany-identifikator")
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("definice", edit, nkd);
        fields.apply("identifikátor", edit, nkd);

        assertThat(edit.getDefinitionModel().getDefinition()).isEqualTo(Map.of("cs", "Publikovaná definice"));
        assertThat(edit.getIdentifier()).isEqualTo("publikovany-identifikator");
    }

    @Test
    void apply_onlyTouchesTheAcceptedField() {
        // The core of the design: everything not accepted stays null, so the edit path's null
        // early-returns leave it untouched.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .identifier("novy")
                .definition(Map.of("cs", "Nová definice"))
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("definice", edit, nkd);

        assertThat(edit.getDefinitionModel().getDefinition()).isEqualTo(Map.of("cs", "Nová definice"));
        assertThat(edit.getIdentifier()).as("unaccepted field must stay null").isNull();
    }

    @Test
    void apply_rangeMapsToDataTypeForProperty_butRangeForRelationship() {
        // A VLASTNOST's range is an XSD datatype (dataType on the edit model); a VZTAH's is a concept IRI.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .range("http://www.w3.org/2001/XMLSchema#string")
                .build();

        PropertyConceptEditModel property = new PropertyConceptEditModel();
        fields.apply("obor-hodnot", property, nkd);
        assertThat(property.getDataType()).isEqualTo("http://www.w3.org/2001/XMLSchema#string");
        assertThat(property.getDomain()).isNull();

        RelationshipConceptEditModel relationship = new RelationshipConceptEditModel();
        fields.apply("obor-hodnot", relationship, nkd);
        assertThat(relationship.getRange()).isEqualTo("http://www.w3.org/2001/XMLSchema#string");
    }

    @Test
    void apply_hierarchyKeyOnWrongType_isNoOpNotCrash() {
        // "nadřazená-třída" belongs to TRIDA; on a VLASTNOST it must no-op rather than ClassCastException.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .broaderClasses(List.of("https://slovník.gov.cz/agendový/104/pojem/nadrazena"))
                .build();
        PropertyConceptEditModel edit = new PropertyConceptEditModel();

        fields.apply("nadřazená-třída", edit, nkd);

        assertThat(edit.getSuperProperty()).isNull();
    }

    @Test
    void apply_ppdf_nullNkdValue_syncsToFalseNotNull() {
        // NKD may omit je-ppdf. Accepting the deviation must resolve to a definitive false, otherwise the
        // edit model carries null and the creator drops the property entirely.
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .isPpdf(null)
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("je-ppdf", edit, nkd);

        assertThat(edit.getIsInPPDF()).isFalse();
    }

    @Test
    void apply_ppdf_trueNkdValue_syncsToTrue() {
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .isPpdf(true)
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("je-ppdf", edit, nkd);

        assertThat(edit.getIsInPPDF()).isTrue();
    }

    @Test
    void apply_unknownKey_isNoOp() {
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("neexistující-vlastnost", edit, ConceptDetailModel.builder().build());

        assertThat(edit.getNameModel()).isNull();
    }

    @Test
    void objectSubjectAndPublicPrivate_areSyncableKeys() {
        assertThat(fields.syncableKeys()).contains(WorkingCopySyncFields.OBJECT_SUBJECT_TYPE_KEY);
        assertThat(fields.syncableKeys()).contains(WorkingCopySyncFields.IS_PUBLIC_KEY);
    }

    @Test
    void apply_objectSubject_setsRoleFromNkdTypes_onClassOnly() {
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .types(List.of("Třída", "Typ subjektu práva")).build();

        ClassConceptEditModel klass = new ClassConceptEditModel();
        fields.apply(WorkingCopySyncFields.OBJECT_SUBJECT_TYPE_KEY, klass, nkd);
        assertThat(klass.getType()).isEqualTo("subjekt");

        // No-op on a non-TRIDA type — it carries no such marker and must not blow up.
        PropertyConceptEditModel property = new PropertyConceptEditModel();
        fields.apply(WorkingCopySyncFields.OBJECT_SUBJECT_TYPE_KEY, property, nkd);
        // PropertyConceptEditModel has no `type` field to set — the apply simply does nothing.
    }

    @Test
    void apply_isPublic_setsClassificationFromNkdTypes() {
        ConceptDetailModel publicNkd = ConceptDetailModel.builder()
                .types(List.of("Třída", "Veřejný údaj")).build();
        ClassConceptEditModel edit = new ClassConceptEditModel();
        fields.apply(WorkingCopySyncFields.IS_PUBLIC_KEY, edit, publicNkd);
        assertThat(edit.getIsPublic()).isTrue();

        ConceptDetailModel privateNkd = ConceptDetailModel.builder()
                .types(List.of("Třída", "Neveřejný údaj")).build();
        ClassConceptEditModel edit2 = new ClassConceptEditModel();
        fields.apply(WorkingCopySyncFields.IS_PUBLIC_KEY, edit2, privateNkd);
        assertThat(edit2.getIsPublic()).isFalse();
    }

    @Test
    void everySyncableKeyIsReportedWhenItsFieldDeviates() {
        PublishedConceptDeviationModel everything = PublishedConceptDeviationModel.builder()
                .types(deviating(null, null))
                .alternativeName(deviating(null, null))
                .definition(deviating(null, null))
                .description(deviating(null, null))
                .identifier(deviating(null, null))
                .exactMatches(deviating(null, null))
                .definingLegalSources(deviating(null, null))
                .relatedLegalSources(deviating(null, null))
                .broaderClasses(deviating(null, null))
                .broaderProperties(deviating(null, null))
                .broaderRelations(deviating(null, null))
                .domain(deviating(null, null))
                .range(deviating(null, null))
                .sharingMethods(deviating(null, null))
                .acquisitionMethod(deviating(null, null))
                .contentType(deviating(null, null))
                .isPpdf(deviating(null, null))
                .objectSubjectType(deviating(null, null))
                .isPublic(deviating(null, null))
                .ais(deviating(null, null))
                .agenda(deviating(null, null))
                .privacyProvisions(deviating(null, null))
                .build();

        assertThat(fields.deviatingSyncableKeys(everything)).containsExactlyElementsOf(fields.syncableKeys());
        assertThat(fields.syncableKeys()).hasSize(21);
    }

    private static final ConceptDetailModel NKD = ConceptDetailModel.builder()
            .types(List.of("Třída", NEVEREJNY_UDAJ_JSON_LD))
            .identifier("nkd-id")
            .exactMatches(List.of("https://example.org/exact"))
            .definingLegalSources(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/1/2000-01-01/dokument/norma/cast_1"))
            .relatedLegalSources(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/2/2000-01-01/dokument/norma/cast_2"))
            .description(Map.of("cs", "Popis z NKD"))
            .sharingMethods(List.of("https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/propojený-datový-fond"))
            .acquisitionMethod("https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/převzetí")
            .contentType("https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/osobní-údaj")
            .isPpdf(true)
            .ais("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/1")
            .agenda("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1")
            .privacyProvisions(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/3/2000-01-01/dokument/norma/cast_3"))
            .build();

    /** Keys every concept type carries, with the edit-model property they write and the expected value. */
    static Stream<Arguments> fieldsSharedByEveryConceptType() {
        List<Supplier<ConceptEditModel>> conceptTypes = List.of(
                ClassConceptEditModel::new, PropertyConceptEditModel::new, RelationshipConceptEditModel::new);
        List<Object[]> fieldsAndValues = List.of(
                new Object[]{"identifikátor", "identifier", NKD.getIdentifier()},
                new Object[]{"ekvivalentní-pojem", "exactMatch", NKD.getExactMatches()},
                new Object[]{"definující-ustanovení-právního-předpisu", "definingLegalSource", NKD.getDefiningLegalSources()},
                new Object[]{"související-ustanovení-právního-předpisu", "relatedLegalSource", NKD.getRelatedLegalSources()},
                new Object[]{"způsob-sdílení-údajů", "sharingMethod", NKD.getSharingMethods()},
                new Object[]{"způsob-získání-údajů", "acquisitionMethod", NKD.getAcquisitionMethod()},
                new Object[]{"typ-obsahu-údajů", "contentType", NKD.getContentType()},
                new Object[]{"je-ppdf", "isInPPDF", Boolean.TRUE},
                new Object[]{WorkingCopySyncFields.IS_PUBLIC_KEY, "isPublic", Boolean.FALSE},
                new Object[]{"ais", "agendaSystemCode", NKD.getAis()},
                new Object[]{"agenda", "agendaCode", NKD.getAgenda()},
                new Object[]{"ustanovení-dokládající-neveřejnost-údaje", "privacyProvisions", NKD.getPrivacyProvisions()});
        return conceptTypes.stream().flatMap(type -> fieldsAndValues.stream().map(f -> {
            ConceptEditModel edit = type.get();
            return Arguments.of(edit.getClass().getSimpleName(), f[0], edit, f[1], f[2]);
        }));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("fieldsSharedByEveryConceptType")
    void sharedFieldTakesTheNkdValueOnEveryConceptType(String type, String key, ConceptEditModel edit,
                                                        String property, Object expected) {
        fields.apply(key, edit, NKD);

        assertThat(new BeanWrapperImpl(edit).getPropertyValue(property)).isEqualTo(expected);
    }

    @Test
    void descriptionIsWrappedIntoTheEditModel() {
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply("popis", edit, NKD);

        assertThat(edit.getDescriptionModel().getDescription()).isEqualTo(Map.of("cs", "Popis z NKD"));
    }

    @Test
    void domainIsWrittenOnPropertyAndRelationshipButNotClass() {
        ConceptDetailModel nkd = ConceptDetailModel.builder().domain("https://example.org/trida").build();
        PropertyConceptEditModel property = new PropertyConceptEditModel();
        RelationshipConceptEditModel relationship = new RelationshipConceptEditModel();
        ClassConceptEditModel clazz = new ClassConceptEditModel();

        fields.apply("definiční-obor", property, nkd);
        fields.apply("definiční-obor", relationship, nkd);
        fields.apply("definiční-obor", clazz, nkd);

        assertThat(property.getDomain()).isEqualTo("https://example.org/trida");
        assertThat(relationship.getDomain()).isEqualTo("https://example.org/trida");
        assertThat(clazz).isEqualTo(new ClassConceptEditModel());
    }

    @Test
    void altNameSkipsBlankAndNonTextValuesAndCollapsesDuplicates() {
        ConceptDetailModel nkd = ConceptDetailModel.builder()
                .alternativeName(Map.of(
                        "cs", List.of(" Jméno ", "Jméno", "", Map.of("x", 1)),
                        "en", "  ",
                        "de", 42))
                .build();
        ClassConceptEditModel edit = new ClassConceptEditModel();

        fields.apply(WorkingCopySyncFields.ALT_NAME_KEY, edit, nkd);

        assertThat(edit.getAltNameModel().getAltName()).containsExactly(Map.entry("cs", List.of("Jméno")));
    }

    @Test
    void publicFromTypesReadsTheMarkerOrNothing() {
        assertThat(WorkingCopySyncFields.publicFromTypes(null)).isNull();
        assertThat(WorkingCopySyncFields.publicFromTypes(List.of("Třída"))).isNull();
        assertThat(WorkingCopySyncFields.publicFromTypes(List.of(VEREJNY_UDAJ_JSON_LD))).isTrue();
        assertThat(WorkingCopySyncFields.publicFromTypes(List.of(NEVEREJNY_UDAJ_JSON_LD))).isFalse();
    }

    @Test
    void objectSubjectRoleReadsTheMarkerOrNothing() {
        assertThat(WorkingCopySyncFields.objectSubjectRole(null)).isNull();
        assertThat(WorkingCopySyncFields.objectSubjectRole(List.of("Třída"))).isNull();
        assertThat(WorkingCopySyncFields.objectSubjectRole(List.of(TOP_JSON_LD))).isEqualTo("objekt");
        assertThat(WorkingCopySyncFields.objectSubjectRole(List.of(TSP_JSON_LD))).isEqualTo("subjekt");
    }
}
