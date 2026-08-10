package com.dia.ismdtoolbackend.models.concept;

import org.apache.jena.ontology.OntologyException;

import java.util.List;
import java.util.Set;

public final class ConceptValidationUtil {

    private static final String NKOD_DATASET_PATTERN = "^https://data\\.gov\\.cz/zdroj/datové-sady/.*$";

    private static final String ABSOLUTE_IRI_PATTERN = "^https?://\\S+$";

    private static final Set<String> ALLOWED_GOVERNANCE_VALUES = Set.of(
            // způsoby-sdílení-údajů
            "veřejně přístupné", "poskytované na žádost", "nesdílené",
            "zpřístupňované pro výkon agendy",
            // způsoby-získání-údajů
            "základních registrů", "jiných agend", "vlastní",
            // typy-obsahu-údajů
            "provozní", "identifikační", "evidenční", "statistické"
    );

    private ConceptValidationUtil() {}

    public static void validateGovernanceValue(String value, String fieldName) {
        boolean valid = ALLOWED_GOVERNANCE_VALUES.stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(value));
        if (!valid) {
            throw new OntologyException(
                    "Neplatná hodnota pro " + fieldName + ": " + value);
        }
    }

    public static void validateGovernanceFields(List<String> sharingMethod,
                                                 String acquisitionMethod,
                                                 String contentType) {
        if (sharingMethod != null && !sharingMethod.isEmpty()) {
            for (String s : sharingMethod) {
                validateGovernanceValue(s, "způsob sdílení");
            }
        }
        if (acquisitionMethod != null && !acquisitionMethod.trim().isEmpty()) {
            validateGovernanceValue(acquisitionMethod, "způsob získání");
        }
        if (contentType != null && !contentType.trim().isEmpty()) {
            validateGovernanceValue(contentType, "typ obsahu");
        }
    }

    public static void validateCodeListDataset(String codeListDataset) {
        if (codeListDataset != null && !codeListDataset.trim().isEmpty() && !codeListDataset.matches(NKOD_DATASET_PATTERN)) {
            throw new OntologyException("Neplatná URL datové sady v NKOD: " + codeListDataset);
        }
    }

    /** The číselník IRI must be an absolute IRI; it identifies the code list itself. */
    public static void validateCodeListIri(String codeListIri) {
        if (codeListIri == null || codeListIri.trim().isEmpty()) {
            return;
        }
        String value = codeListIri.trim();
        if (!value.matches(ABSOLUTE_IRI_PATTERN)) {
            throw new OntologyException("Neplatné IRI číselníku: " + codeListIri);
        }
    }

    /**
     * Both code-list IRIs are mandatory together: a class either has no code list at all,
     * or it declares the číselník IRI and its NKOD dataset IRI.
     */
    public static void validateCodeListCompleteness(String codeListIri, String codeListDataset) {
        boolean hasIri = codeListIri != null && !codeListIri.trim().isEmpty();
        boolean hasDataset = codeListDataset != null && !codeListDataset.trim().isEmpty();

        if (hasIri && !hasDataset) {
            throw new OntologyException(
                    "Je uvedeno IRI číselníku, ale chybí IRI zastřešující datové sady v NKOD");
        }
        if (hasDataset && !hasIri) {
            throw new OntologyException(
                    "Je uvedena datová sada v NKOD, ale chybí IRI číselníku");
        }
    }

    public static void validatePrivacyPublicConflict(List<String> privacyProvisions,
                                                      Boolean isPublic,
                                                      String entityName,
                                                      String genderSuffix) {
        if (privacyProvisions != null && !privacyProvisions.isEmpty()
                && isPublic != null && isPublic) {
            throw new OntologyException(
                    entityName + " nemůže být současně veřejn" + genderSuffix
                            + " a mít ustanovení o neveřejnosti");
        }
    }
}
