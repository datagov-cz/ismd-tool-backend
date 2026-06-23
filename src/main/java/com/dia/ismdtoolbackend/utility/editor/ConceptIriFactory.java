package com.dia.ismdtoolbackend.utility.editor;

import com.dia.utility.DataTypeConverter;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;

import static com.dia.constants.VocabularyConstants.*;

/**
 * Owns all IRI / namespace generation for the concept editor.
 *
 * <p>Deliberately a plain class (NOT a Spring {@code @Component}) holding its own
 * {@link URIGenerator}. {@link ConceptEditor} instantiates it as a {@code new}
 * field exactly as it previously held {@code new URIGenerator()}. Making this an
 * injected bean would cause Mockito's {@code @InjectMocks ConceptEditor} in the
 * test base to inject a mock here, replacing the real URI generation and breaking
 * the URI-generation assertions.
 *
 * <p>The effective namespace is per-edit mutable state set once at the start of
 * each {@code editConcept}, mirroring the previous behavior.
 */
class ConceptIriFactory {

    private final URIGenerator uriGenerator = new URIGenerator();

    /**
     * Resolves and sets the effective namespace for the current edit. Falls back
     * to {@link com.dia.constants.VocabularyConstants#DEFAULT_NS} when the supplied
     * namespace is blank or not a valid IRI.
     */
    void useEffectiveNamespace(String namespace) {
        uriGenerator.setEffectiveNamespace(determineEffectiveNamespace(namespace));
    }

    String getEffectiveNamespace() {
        return uriGenerator.getEffectiveNamespace();
    }

    String generateConceptURI(String name, String identifier) {
        return uriGenerator.generateConceptURI(name, identifier);
    }

    private String determineEffectiveNamespace(String namespace) {
        if (namespace != null && !namespace.trim().isEmpty() && UtilityMethods.isValidIRI(namespace)) {
            return UtilityMethods.ensureNamespaceEndsWithDelimiter(namespace);
        }
        return DEFAULT_NS;
    }

    /**
     * Maps a governance code-list value to its canonical číselník položka IRI.
     * Returns {@code null} for property names that have no governance code list.
     */
    String generateGovernanceIRI(String value, String propertyName) {
        String sanitized = UtilityMethods.sanitizeForIRI(value);
        return switch (propertyName) {
            case TYP_OBSAHU -> "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/" + sanitized;
            case ZPUSOB_SDILENI -> "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/" + sanitized;
            case ZPUSOB_ZISKANI -> "https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/" + sanitized;
            default -> null;
        };
    }

    /** True when the value is already a URI; mirrors {@link DataTypeConverter#isUri(String)}. */
    boolean isUri(String value) {
        return DataTypeConverter.isUri(value);
    }
}
