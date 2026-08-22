package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptValidationUtil;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.utility.UtilityMethods;

import java.util.List;

/**
 * The single pre-flight validation rule set for a concept payload, shared by create
 * and edit so the same input is accepted or rejected identically on both verbs.
 *
 * <p>Runs BEFORE any model mutation and collects every offending input, so the whole
 * request can be rejected atomically (HTTP 400) with one message listing all problems
 * — rather than throwing on the first offender or silently dropping values.
 *
 * <p>ELI validation is structural only: the host is canonicalized and checked against
 * the canonical e-Sbírka prefix. e-Sbírka is never contacted, so a syntactically valid
 * IRI naming a law that does not exist is accepted; labels resolve lazily at read time.
 *
 * <p>A {@code null} field means "not provided" and a blank string / empty list means
 * "clear" — neither is an error. Only a genuinely malformed value is reported.
 *
 * <p>Plain class (not a Spring bean) so it is not replaced by a Mockito mock under
 * {@code @InjectMocks ConceptEditor}.
 */
public final class ConceptInputValidator {

    /** A single rejected input: which field, the offending value, and why. */
    public record InvalidInput(String field, String value, String reason) {
        @Override
        public String toString() {
            return field + "='" + value + "' (" + reason + ")";
        }
    }

    public static final String REASON_ELI = "není kanonické e-Sbírka ELI IRI";
    public static final String REASON_AGENDA = "neplatný kód agendy";
    public static final String REASON_AIS = "neplatný kód AIS";
    public static final String REASON_URL = "URL je prázdné nebo neplatné";
    public static final String REASON_IRI = "neplatné IRI";

    private ConceptInputValidator() {
    }

    /** Validates a create payload. */
    public static List<InvalidInput> validate(ConceptCreateModel createModel) {
        return createModel == null ? List.of() : validate(ConceptInputView.of(createModel));
    }

    /** Validates an edit payload. */
    public static List<InvalidInput> validate(ConceptEditModel editModel) {
        return editModel == null ? List.of() : validate(ConceptInputView.of(editModel));
    }

    /** Validates the flattened field surface shared by both verbs. */
    public static List<InvalidInput> validate(ConceptInputView v) {
        List<InvalidInput> problems = new java.util.ArrayList<>();
        if (v == null) {
            return problems;
        }

        validateEliList("definingLegalSource", v.definingLegalSource(), problems);
        validateEliList("relatedLegalSource", v.relatedLegalSource(), problems);
        validateEliList("privacyProvisions", v.privacyProvisions(), problems);

        validateNonLegalList("definingNonLegalSource", v.definingNonLegalSource(), problems);
        validateNonLegalList("relatedNonLegalSource", v.relatedNonLegalSource(), problems);

        validateExactMatch(v.exactMatch(), problems);

        validateAgenda(v.agendaCode(), problems);
        validateAis(v.agendaSystemCode(), problems);

        validateDomainRules(v, problems);

        return problems;
    }

    /**
     * Runs the {@link ConceptValidationUtil} domain rules (privacy/public conflict,
     * NKOD code-list URL, governance-value allowlist) and folds any violation into the
     * collected problems, so the request is rejected atomically with one 400 rather
     * than throwing per-rule.
     */
    private static void validateDomainRules(ConceptInputView v, List<InvalidInput> problems) {
        record Check(String field, Runnable rule) {}

        // The code-list rules are no-ops for property/relationship, whose view carries
        // null code-list fields — only Třída has a číselník.
        List<Check> checks = List.of(
                new Check("isPublic", () -> ConceptValidationUtil.validatePrivacyPublicConflict(
                        v.privacyProvisions(), v.isPublic(), v.entityName(), v.genderSuffix())),
                new Check("codeListDataset", () -> ConceptValidationUtil.validateCodeListDataset(v.codeListDataset())),
                new Check("codeListIri", () -> ConceptValidationUtil.validateCodeListIri(v.codeListIri())),
                new Check("codeListIri", () -> ConceptValidationUtil.validateCodeListCompleteness(
                        v.codeListIri(), v.codeListDataset())),
                new Check("governance", () -> ConceptValidationUtil.validateGovernanceFields(
                        v.sharingMethod(), v.acquisitionMethod(), v.contentType()))
        );

        for (Check check : checks) {
            try {
                check.rule().run();
            } catch (RuntimeException e) {
                problems.add(new InvalidInput(check.field(), null, e.getMessage()));
            }
        }
    }

    /**
     * Legacy e-Sbírka hosts (.cz) are canonicalized to .gov.cz before the check, matching the
     * read/parse path — so a legacy IRI is accepted here and stored canonically downstream.
     */
    private static void validateEliList(String field, List<String> values, List<InvalidInput> problems) {
        if (values == null) return;
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;   // blank entry = ignored, not invalid
            String trimmed = v.trim();
            String canonical = EsbirkaEliParser.canonicalizeHost(trimmed);
            if (!SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                problems.add(new InvalidInput(field, trimmed, REASON_ELI));
            }
        }
    }

    private static void validateNonLegalList(String field, List<DigitalObjectModel> values,
                                             List<InvalidInput> problems) {
        if (values == null) return;
        for (DigitalObjectModel d : values) {
            String url = d == null ? null : d.getUrl();
            if (url == null || url.trim().isEmpty() || !UtilityMethods.isValidUrl(url.trim())) {
                problems.add(new InvalidInput(field, url, REASON_URL));
            }
        }
    }

    private static void validateExactMatch(List<String> values, List<InvalidInput> problems) {
        if (values == null) return;
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;   // blank entry = ignored
            String trimmed = v.trim();
            if (!UtilityMethods.isValidIRI(trimmed)) {
                problems.add(new InvalidInput("exactMatch", trimmed, REASON_IRI));
            }
        }
    }

    private static void validateAgenda(String agendaCode, List<InvalidInput> problems) {
        if (agendaCode == null || agendaCode.trim().isEmpty()) return;   // blank = clear
        if (!UtilityMethods.isValidAgendaValue(agendaCode)) {
            problems.add(new InvalidInput("agendaCode", agendaCode, REASON_AGENDA));
        }
    }

    private static void validateAis(String aisCode, List<InvalidInput> problems) {
        if (aisCode == null || aisCode.trim().isEmpty()) return;   // blank = clear
        if (!UtilityMethods.isValidAISValue(aisCode)) {
            problems.add(new InvalidInput("agendaSystemCode", aisCode, REASON_AIS));
        }
    }
}
