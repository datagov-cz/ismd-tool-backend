package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;
import com.dia.ismdtoolbackend.utility.validation.ConceptInputValidator.InvalidInput;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One rule set, both verbs. The parity tests are the point of this class: the same
 * payload must be accepted or rejected identically whether it arrives as a create or
 * an edit — create used to silently drop values that edit rejected with a 400.
 */
class ConceptInputValidatorTest {

    private static final String VALID_FRAGMENT =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2024-01-01/dokument/norma/cast_1";
    private static final String VALID_LAW =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String LEGACY_HOST =
            "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String BAD_ELI = "http://example.org/not-eli";

    private static ClassConceptModel createModel() {
        ClassConceptModel m = new ClassConceptModel();
        m.setConceptType("TRIDA");
        return m;
    }

    private static ClassConceptEditModel editModel() {
        ClassConceptEditModel m = new ClassConceptEditModel();
        m.setConceptType("TRIDA");
        return m;
    }

    private static List<String> fields(List<InvalidInput> problems) {
        List<String> out = new ArrayList<>();
        for (InvalidInput p : problems) out.add(p.field());
        return out;
    }

    private static DigitalObjectModel doc(String url) {
        DigitalObjectModel d = new DigitalObjectModel();
        d.setUrl(url);
        return d;
    }

    @Nested
    class EliValidation {

        @Test
        void acceptsValidEliAcrossAllThreeFields() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(List.of(VALID_FRAGMENT));
            m.setRelatedLegalSource(List.of(VALID_LAW));
            m.setPrivacyProvisions(List.of(VALID_FRAGMENT));
            m.setIsPublic(false);

            assertTrue(ConceptInputValidator.validate(m).isEmpty());
        }

        @Test
        void acceptsLegacyHostThatCanonicalizes() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(List.of(LEGACY_HOST));

            assertTrue(ConceptInputValidator.validate(m).isEmpty(),
                    "legacy e-Sbírka host must canonicalize and be accepted");
        }

        @Test
        void rejectsNonEsbirkaIriAndNamesFieldAndValue() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(List.of(BAD_ELI));

            List<InvalidInput> problems = ConceptInputValidator.validate(m);

            assertEquals(1, problems.size());
            assertEquals("definingLegalSource", problems.get(0).field());
            assertEquals(BAD_ELI, problems.get(0).value());
            assertEquals(ConceptInputValidator.REASON_ELI, problems.get(0).reason());
        }

        @Test
        void rejectsCombinedEliValue() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(List.of(VALID_LAW + ";" + VALID_LAW));

            assertEquals(List.of("definingLegalSource"), fields(ConceptInputValidator.validate(m)));
        }

        @Test
        void nullAndBlankEntriesAreNotErrors() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(null);
            m.setRelatedLegalSource(Arrays.asList("   ", null));
            m.setPrivacyProvisions(List.of());

            assertTrue(ConceptInputValidator.validate(m).isEmpty());
        }
    }

    @Nested
    class GapsCreatePreviouslyMissed {

        @Test
        void rejectsPublicConceptCarryingPrivacyProvisions() {
            ClassConceptModel m = createModel();
            m.setIsPublic(true);
            m.setPrivacyProvisions(List.of(VALID_FRAGMENT));

            assertTrue(fields(ConceptInputValidator.validate(m)).contains("isPublic"),
                    "a concept cannot be public and carry non-public provisions");
        }

        @Test
        void rejectsOffAllowlistGovernanceValues() {
            ClassConceptModel m = createModel();
            m.setContentType("naprosto vymyšlený typ");

            assertTrue(fields(ConceptInputValidator.validate(m)).contains("governance"),
                    "off-allowlist governance values used to be minted into codelist IRIs");
        }

        @Test
        void acceptsAllowlistedGovernanceValues() {
            ClassConceptModel m = createModel();
            m.setContentType("provozní");
            m.setAcquisitionMethod("vlastní");
            m.setSharingMethod(List.of("veřejně přístupné"));

            assertTrue(ConceptInputValidator.validate(m).isEmpty());
        }

        @Test
        void rejectsMalformedAgendaAndAisCodes() {
            ClassConceptModel m = createModel();
            m.setAgendaCode("not-an-agenda");
            m.setAgendaSystemCode("not-an-ais");

            List<String> f = fields(ConceptInputValidator.validate(m));
            assertTrue(f.contains("agendaCode"));
            assertTrue(f.contains("agendaSystemCode"));
        }

        @Test
        void rejectsInvalidExactMatchIri() {
            ClassConceptModel m = createModel();
            m.setExactMatch(List.of("not-a-valid-iri"));

            assertEquals(List.of("exactMatch"), fields(ConceptInputValidator.validate(m)));
        }

        @Test
        void rejectsBlankNonLegalSourceUrl() {
            ClassConceptModel m = createModel();
            m.setDefiningNonLegalSource(List.of(doc("   ")));

            assertEquals(List.of("definingNonLegalSource"), fields(ConceptInputValidator.validate(m)));
        }
    }

    @Nested
    class CreateEditParity {

        @Test
        void sameBadEliRejectedOnBothVerbs() {
            ClassConceptModel c = createModel();
            c.setDefiningLegalSource(List.of(BAD_ELI));
            ClassConceptEditModel e = editModel();
            e.setDefiningLegalSource(List.of(BAD_ELI));

            assertEquals(fields(ConceptInputValidator.validate(e)),
                    fields(ConceptInputValidator.validate(c)));
        }

        @Test
        void samePrivacyConflictRejectedOnBothVerbs() {
            ClassConceptModel c = createModel();
            c.setIsPublic(true);
            c.setPrivacyProvisions(List.of(VALID_FRAGMENT));
            ClassConceptEditModel e = editModel();
            e.setIsPublic(true);
            e.setPrivacyProvisions(List.of(VALID_FRAGMENT));

            assertEquals(fields(ConceptInputValidator.validate(e)),
                    fields(ConceptInputValidator.validate(c)));
        }

        @Test
        void sameGovernanceViolationRejectedOnBothVerbs() {
            ClassConceptModel c = createModel();
            c.setAcquisitionMethod("vymyšlený způsob");
            ClassConceptEditModel e = editModel();
            e.setAcquisitionMethod("vymyšlený způsob");

            assertEquals(fields(ConceptInputValidator.validate(e)),
                    fields(ConceptInputValidator.validate(c)));
        }

        @Test
        void sameValidPayloadAcceptedOnBothVerbs() {
            ClassConceptModel c = createModel();
            c.setDefiningLegalSource(List.of(VALID_LAW));
            c.setContentType("provozní");
            ClassConceptEditModel e = editModel();
            e.setDefiningLegalSource(List.of(VALID_LAW));
            e.setContentType("provozní");

            assertTrue(ConceptInputValidator.validate(c).isEmpty());
            assertTrue(ConceptInputValidator.validate(e).isEmpty());
        }
    }

    @Nested
    class AllConceptTypes {

        @Test
        void validatesPrivacyProvisionsOnPropertyConcept() {
            PropertyConceptModel m = new PropertyConceptModel();
            m.setConceptType("VLASTNOST");
            m.setPrivacyProvisions(List.of(BAD_ELI));

            assertEquals(List.of("privacyProvisions"), fields(ConceptInputValidator.validate(m)));
        }

        @Test
        void validatesPrivacyProvisionsOnRelationshipConcept() {
            RelationshipConceptModel m = new RelationshipConceptModel();
            m.setConceptType("VZTAH");
            m.setPrivacyProvisions(List.of(BAD_ELI));

            assertEquals(List.of("privacyProvisions"), fields(ConceptInputValidator.validate(m)));
        }

        @Test
        void codeListRulesAreClassOnly() {
            // Property/relationship views carry null code-list fields, so the rules are no-ops.
            PropertyConceptModel p = new PropertyConceptModel();
            p.setConceptType("VLASTNOST");

            assertTrue(ConceptInputValidator.validate(p).isEmpty());
        }

        @Test
        void rejectsIncompleteCodeListOnClass() {
            ClassConceptModel m = createModel();
            m.setCodeListIri("https://data.gov.cz/zdroj/číselník/x");

            assertTrue(fields(ConceptInputValidator.validate(m)).contains("codeListIri"),
                    "code-list IRI and dataset are mandatory together");
        }
    }

    @Nested
    class Aggregation {

        @Test
        void collectsEveryOffenderRatherThanFailingFast() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(List.of(BAD_ELI, VALID_FRAGMENT));
            m.setRelatedLegalSource(List.of(BAD_ELI));
            m.setPrivacyProvisions(List.of(BAD_ELI));
            m.setExactMatch(List.of("not-a-valid-iri"));
            m.setAgendaCode("bogus");

            List<InvalidInput> problems = ConceptInputValidator.validate(m);

            assertTrue(problems.size() >= 5, "expected every offender, got: " + problems);
            List<String> f = fields(problems);
            assertTrue(f.contains("definingLegalSource"));
            assertTrue(f.contains("relatedLegalSource"));
            assertTrue(f.contains("privacyProvisions"));
            assertTrue(f.contains("exactMatch"));
            assertTrue(f.contains("agendaCode"));
        }

        @Test
        void nullModelYieldsNoProblems() {
            assertTrue(ConceptInputValidator.validate((ClassConceptModel) null).isEmpty());
            assertTrue(ConceptInputValidator.validate((ClassConceptEditModel) null).isEmpty());
        }
    }
}
