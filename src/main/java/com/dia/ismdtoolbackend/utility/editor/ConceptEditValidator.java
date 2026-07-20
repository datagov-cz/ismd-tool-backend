package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptValidationUtil;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.utility.UtilityMethods;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-flight validation for a concept edit. Runs BEFORE any model mutation and
 * collects every offending input so the whole edit can be rejected atomically
 * (HTTP 400) with one message listing all problems — rather than silently
 * dropping invalid values.
 *
 * <p>The validity predicates mirror exactly the checks the field updaters apply
 * ({@link SparqlIriValidator#isEsbirkaEliIri}, {@link UtilityMethods#isValidAgendaValue},
 * {@link UtilityMethods#isValidAISValue}, {@link UtilityMethods#isValidUrl},
 * {@link UtilityMethods#isValidIRI}). A {@code null} field means "not provided"
 * and a blank string / empty list means "clear" — neither is an error. Only a
 * genuinely malformed value is reported.
 *
 * <p>Plain class (not a Spring bean) for the same reason as the other editor
 * collaborators: it must not be replaced by a Mockito mock under
 * {@code @InjectMocks ConceptEditor}.
 */
class ConceptEditValidator {

    /** A single rejected input: which field, the offending value, and why. */
    record InvalidInput(String field, String value, String reason) {
        @Override
        public String toString() {
            return field + "='" + value + "' (" + reason + ")";
        }
    }

    private static final String REASON_ELI = "není kanonické e-Sbírka ELI IRI";
    private static final String REASON_AGENDA = "neplatný kód agendy";
    private static final String REASON_AIS = "neplatný kód AIS";
    private static final String REASON_URL = "URL je prázdné nebo neplatné";
    private static final String REASON_IRI = "neplatné IRI";

    List<InvalidInput> validate(ConceptEditModel editModel) {
        List<InvalidInput> problems = new ArrayList<>();

        // Common fields (all concept types)
        validateEliList("definingLegalSource", editModel.getDefiningLegalSource(), problems);
        validateEliList("relatedLegalSource", editModel.getRelatedLegalSource(), problems);
        validateNonLegalList("definingNonLegalSource", editModel.getDefiningNonLegalSource(), problems);
        validateNonLegalList("relatedNonLegalSource", editModel.getRelatedNonLegalSource(), problems);
        validateExactMatch(editModel.getExactMatch(), problems);

        // Governance metadata (agendaCode / agendaSystemCode live on the concrete
        // *EditModel subtypes, not the abstract base — resolve per type).
        validateAgenda(agendaCodeOf(editModel), problems);
        validateAis(agendaSystemCodeOf(editModel), problems);

        // Privacy provisions are carried by all three concept types (class,
        // property, relationship) — they can each be public/non-public.
        validateEliList("privacyProvisions", privacyProvisionsOf(editModel), problems);

        // Domain rules previously declared on the *EditModel classes
        // (validateSpecificFields) but never invoked in the live flow. Folded in
        // here so they actually run on edit. The models stay pure data classes;
        // ConceptValidationUtil remains the single source of truth for the rules.
        validateDomainRules(editModel, problems);

        return problems;
    }

    /** Per-type bundle of the governance/privacy fields the domain rules check. */
    private record GovernanceBundle(List<String> privacyProvisions, Boolean isPublic,
                                    String codeListDataset, List<String> sharingMethod,
                                    String acquisitionMethod, String contentType,
                                    String entityName, String genderSuffix) {}

    private GovernanceBundle governanceOf(ConceptEditModel editModel) {
        if (editModel instanceof ClassConceptEditModel c) {
            return new GovernanceBundle(c.getPrivacyProvisions(), c.getIsPublic(), c.getCodeListDataset(),
                    c.getSharingMethod(), c.getAcquisitionMethod(), c.getContentType(), "Třída", "á");
        }
        if (editModel instanceof PropertyConceptEditModel p) {
            return new GovernanceBundle(p.getPrivacyProvisions(), p.getIsPublic(), p.getCodeListDataset(),
                    p.getSharingMethod(), p.getAcquisitionMethod(), p.getContentType(), "Vlastnost", "á");
        }
        if (editModel instanceof RelationshipConceptEditModel r) {
            return new GovernanceBundle(r.getPrivacyProvisions(), r.getIsPublic(), r.getCodeListDataset(),
                    r.getSharingMethod(), r.getAcquisitionMethod(), r.getContentType(), "Vztah", "ý");
        }
        return null;
    }

    /**
     * Runs the {@link ConceptValidationUtil} domain rules (privacy/public conflict,
     * NKOD code-list URL, governance-value allowlist) and folds any violation into
     * the collected problems as an {@link InvalidInput}, so the whole edit is
     * rejected atomically with one 400 rather than throwing per-rule.
     */
    private void validateDomainRules(ConceptEditModel editModel, List<InvalidInput> problems) {
        GovernanceBundle g = governanceOf(editModel);
        if (g == null) return;

        record Check(String field, Runnable rule) {}
        List<Check> checks = List.of(
                new Check("isPublic", () -> ConceptValidationUtil.validatePrivacyPublicConflict(
                        g.privacyProvisions(), g.isPublic(), g.entityName(), g.genderSuffix())),
                new Check("codeListDataset", () -> ConceptValidationUtil.validateCodeListDataset(g.codeListDataset())),
                new Check("governance", () -> ConceptValidationUtil.validateGovernanceFields(
                        g.sharingMethod(), g.acquisitionMethod(), g.contentType()))
        );

        for (Check check : checks) {
            try {
                check.rule().run();
            } catch (RuntimeException e) {
                problems.add(new InvalidInput(check.field(), null, e.getMessage()));
            }
        }
    }

    private List<String> privacyProvisionsOf(ConceptEditModel editModel) {
        if (editModel instanceof ClassConceptEditModel c) return c.getPrivacyProvisions();
        if (editModel instanceof PropertyConceptEditModel p) return p.getPrivacyProvisions();
        if (editModel instanceof RelationshipConceptEditModel r) return r.getPrivacyProvisions();
        return null;
    }

    private String agendaCodeOf(ConceptEditModel editModel) {
        if (editModel instanceof ClassConceptEditModel c) return c.getAgendaCode();
        if (editModel instanceof PropertyConceptEditModel p) return p.getAgendaCode();
        if (editModel instanceof RelationshipConceptEditModel r) return r.getAgendaCode();
        return null;
    }

    private String agendaSystemCodeOf(ConceptEditModel editModel) {
        if (editModel instanceof ClassConceptEditModel c) return c.getAgendaSystemCode();
        if (editModel instanceof PropertyConceptEditModel p) return p.getAgendaSystemCode();
        if (editModel instanceof RelationshipConceptEditModel r) return r.getAgendaSystemCode();
        return null;
    }

    private void validateEliList(String field, List<String> values, List<InvalidInput> problems) {
        if (values == null) return;
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;   // blank entry = ignored, not invalid
            String trimmed = v.trim();
            if (!SparqlIriValidator.isEsbirkaEliIri(trimmed)) {
                problems.add(new InvalidInput(field, trimmed, REASON_ELI));
            }
        }
    }

    private void validateNonLegalList(String field, List<DigitalObjectModel> values, List<InvalidInput> problems) {
        if (values == null) return;
        for (DigitalObjectModel d : values) {
            String url = d == null ? null : d.getUrl();
            if (url == null || url.trim().isEmpty() || !UtilityMethods.isValidUrl(url.trim())) {
                problems.add(new InvalidInput(field, url, REASON_URL));
            }
        }
    }

    private void validateExactMatch(List<String> values, List<InvalidInput> problems) {
        if (values == null) return;
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;   // blank entry = ignored
            String trimmed = v.trim();
            if (!UtilityMethods.isValidIRI(trimmed)) {
                problems.add(new InvalidInput("exactMatch", trimmed, REASON_IRI));
            }
        }
    }

    private void validateAgenda(String agendaCode, List<InvalidInput> problems) {
        if (agendaCode == null || agendaCode.trim().isEmpty()) return;   // blank = clear
        if (!UtilityMethods.isValidAgendaValue(agendaCode)) {
            problems.add(new InvalidInput("agendaCode", agendaCode, REASON_AGENDA));
        }
    }

    private void validateAis(String aisCode, List<InvalidInput> problems) {
        if (aisCode == null || aisCode.trim().isEmpty()) return;   // blank = clear
        if (!UtilityMethods.isValidAISValue(aisCode)) {
            problems.add(new InvalidInput("agendaSystemCode", aisCode, REASON_AIS));
        }
    }
}
