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

import static org.assertj.core.api.Assertions.assertThat;

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

            assertThat(ConceptInputValidator.validate(m)).isEmpty();
        }

        @Test
        void acceptsLegacyHostThatCanonicalizes() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(List.of(LEGACY_HOST));

            assertThat(ConceptInputValidator.validate(m))
                    .as("legacy e-Sbírka host must canonicalize and be accepted")
                    .isEmpty();
        }

        @Test
        void rejectsNonEsbirkaIriAndNamesFieldAndValue() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(List.of(BAD_ELI));

            List<InvalidInput> problems = ConceptInputValidator.validate(m);

            assertThat(problems).hasSize(1);
            assertThat(problems.get(0).field()).isEqualTo("definingLegalSource");
            assertThat(problems.get(0).value()).isEqualTo(BAD_ELI);
            assertThat(problems.get(0).reason()).isEqualTo(ConceptInputValidator.REASON_ELI);
        }

        @Test
        void rejectsCombinedEliValue() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(List.of(VALID_LAW + ";" + VALID_LAW));

            assertThat(fields(ConceptInputValidator.validate(m))).isEqualTo(List.of("definingLegalSource"));
        }

        @Test
        void nullAndBlankEntriesAreNotErrors() {
            ClassConceptModel m = createModel();
            m.setDefiningLegalSource(null);
            m.setRelatedLegalSource(Arrays.asList("   ", null));
            m.setPrivacyProvisions(List.of());

            assertThat(ConceptInputValidator.validate(m)).isEmpty();
        }
    }

    @Nested
    class GapsCreatePreviouslyMissed {

        @Test
        void rejectsPublicConceptCarryingPrivacyProvisions() {
            ClassConceptModel m = createModel();
            m.setIsPublic(true);
            m.setPrivacyProvisions(List.of(VALID_FRAGMENT));

            assertThat(fields(ConceptInputValidator.validate(m)))
                    .as("a concept cannot be public and carry non-public provisions")
                    .contains("isPublic");
        }

        @Test
        void rejectsOffAllowlistGovernanceValues() {
            ClassConceptModel m = createModel();
            m.setContentType("naprosto vymyšlený typ");

            assertThat(fields(ConceptInputValidator.validate(m)))
                    .as("off-allowlist governance values used to be minted into codelist IRIs")
                    .contains("governance");
        }

        @Test
        void acceptsAllowlistedGovernanceValues() {
            ClassConceptModel m = createModel();
            m.setContentType("provozní");
            m.setAcquisitionMethod("vlastní");
            m.setSharingMethod(List.of("veřejně přístupné"));

            assertThat(ConceptInputValidator.validate(m)).isEmpty();
        }

        @Test
        void acceptsEveryAllowlistedGovernanceValue() {
            List<String> sharing = List.of("veřejně přístupné", "poskytované na žádost",
                    "nesdílené", "zpřístupňované pro výkon agendy");
            List<String> acquisition = List.of("základních registrů", "jiných agend", "vlastní");
            List<String> content = List.of("provozní", "identifikační", "evidenční", "statistické");

            for (String s : sharing) {
                ClassConceptModel m = createModel();
                m.setSharingMethod(List.of(s));
                assertThat(ConceptInputValidator.validate(m)).as("sharing method rejected: " + s).isEmpty();
            }
            for (String a : acquisition) {
                ClassConceptModel m = createModel();
                m.setAcquisitionMethod(a);
                assertThat(ConceptInputValidator.validate(m)).as("acquisition method rejected: " + a).isEmpty();
            }
            for (String c : content) {
                ClassConceptModel m = createModel();
                m.setContentType(c);
                assertThat(ConceptInputValidator.validate(m)).as("content type rejected: " + c).isEmpty();
            }
        }

        @Test
        void rejectsMalformedAgendaAndAisCodes() {
            ClassConceptModel m = createModel();
            m.setAgendaCode("not-an-agenda");
            m.setAgendaSystemCode("not-an-ais");

            List<String> f = fields(ConceptInputValidator.validate(m));
            assertThat(f).contains("agendaCode");
            assertThat(f).contains("agendaSystemCode");
        }

        @Test
        void rejectsInvalidExactMatchIri() {
            ClassConceptModel m = createModel();
            m.setExactMatch(List.of("not-a-valid-iri"));

            assertThat(fields(ConceptInputValidator.validate(m))).isEqualTo(List.of("exactMatch"));
        }

        @Test
        void rejectsBlankNonLegalSourceUrl() {
            ClassConceptModel m = createModel();
            m.setDefiningNonLegalSource(List.of(doc("   ")));

            assertThat(fields(ConceptInputValidator.validate(m))).isEqualTo(List.of("definingNonLegalSource"));
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

            assertThat(fields(ConceptInputValidator.validate(c))).isEqualTo(fields(ConceptInputValidator.validate(e)));
        }

        @Test
        void samePrivacyConflictRejectedOnBothVerbs() {
            ClassConceptModel c = createModel();
            c.setIsPublic(true);
            c.setPrivacyProvisions(List.of(VALID_FRAGMENT));
            ClassConceptEditModel e = editModel();
            e.setIsPublic(true);
            e.setPrivacyProvisions(List.of(VALID_FRAGMENT));

            assertThat(fields(ConceptInputValidator.validate(c))).isEqualTo(fields(ConceptInputValidator.validate(e)));
        }

        @Test
        void sameGovernanceViolationRejectedOnBothVerbs() {
            ClassConceptModel c = createModel();
            c.setAcquisitionMethod("vymyšlený způsob");
            ClassConceptEditModel e = editModel();
            e.setAcquisitionMethod("vymyšlený způsob");

            assertThat(fields(ConceptInputValidator.validate(c))).isEqualTo(fields(ConceptInputValidator.validate(e)));
        }

        @Test
        void sameValidPayloadAcceptedOnBothVerbs() {
            ClassConceptModel c = createModel();
            c.setDefiningLegalSource(List.of(VALID_LAW));
            c.setContentType("provozní");
            ClassConceptEditModel e = editModel();
            e.setDefiningLegalSource(List.of(VALID_LAW));
            e.setContentType("provozní");

            assertThat(ConceptInputValidator.validate(c)).isEmpty();
            assertThat(ConceptInputValidator.validate(e)).isEmpty();
        }
    }

    @Nested
    class AllConceptTypes {

        @Test
        void validatesPrivacyProvisionsOnPropertyConcept() {
            PropertyConceptModel m = new PropertyConceptModel();
            m.setConceptType("VLASTNOST");
            m.setPrivacyProvisions(List.of(BAD_ELI));

            assertThat(fields(ConceptInputValidator.validate(m))).isEqualTo(List.of("privacyProvisions"));
        }

        @Test
        void validatesPrivacyProvisionsOnRelationshipConcept() {
            RelationshipConceptModel m = new RelationshipConceptModel();
            m.setConceptType("VZTAH");
            m.setPrivacyProvisions(List.of(BAD_ELI));

            assertThat(fields(ConceptInputValidator.validate(m))).isEqualTo(List.of("privacyProvisions"));
        }

        @Test
        void codeListRulesAreClassOnly() {
            // Property/relationship views carry null code-list fields, so the rules are no-ops.
            PropertyConceptModel p = new PropertyConceptModel();
            p.setConceptType("VLASTNOST");

            assertThat(ConceptInputValidator.validate(p)).isEmpty();
        }

        @Test
        void rejectsIncompleteCodeListOnClass() {
            ClassConceptModel m = createModel();
            m.setCodeListIri("https://data.gov.cz/zdroj/číselník/x");

            assertThat(fields(ConceptInputValidator.validate(m)))
                    .as("code-list IRI and dataset are mandatory together")
                    .contains("codeListIri");
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

            assertThat(problems).as("expected every offender, got: " + problems).hasSizeGreaterThanOrEqualTo(5);
            List<String> f = fields(problems);
            assertThat(f).contains("definingLegalSource");
            assertThat(f).contains("relatedLegalSource");
            assertThat(f).contains("privacyProvisions");
            assertThat(f).contains("exactMatch");
            assertThat(f).contains("agendaCode");
        }

        @Test
        void nullModelYieldsNoProblems() {
            assertThat(ConceptInputValidator.validate((ClassConceptModel) null)).isEmpty();
            assertThat(ConceptInputValidator.validate((ClassConceptEditModel) null)).isEmpty();
        }
    }

    @Test
    void invalidInputWithoutAValuePrintsOnlyItsReason() {
        assertThat(new InvalidInput("isPublic", null, "Třída nemůže být současně veřejná").toString())
                .isEqualTo("Třída nemůže být současně veřejná");
        assertThat(new InvalidInput("exactMatch", "x", "neplatné IRI").toString())
                .isEqualTo("exactMatch='x' (neplatné IRI)");
    }
}
