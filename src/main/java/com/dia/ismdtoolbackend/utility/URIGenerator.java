package com.dia.ismdtoolbackend.utility;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.DEFAULT_NS;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.GLOBAL_SHACL_BASE_URI;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.LOCAL_SHACL_BASE_URI;

/**
 * URI Generator helper class
 */
@Setter
@Getter
@Slf4j
public class URIGenerator {
    private String effectiveNamespace = DEFAULT_NS;

    private String vocabularyName;

    public String generateConceptURI(String conceptName, String identifier) {
        if (identifier != null && !identifier.trim().isEmpty()) {
            String trimmedIdentifier = UtilityMethods.removeTrailingSlash(identifier.trim());

            if (UtilityMethods.isValidIRI(trimmedIdentifier)) {
                if (trimmedIdentifier.contains("/pojem/")) {
                    return trimmedIdentifier;
                }
                String conceptPart = extractConceptName(trimmedIdentifier);
                return buildConceptURI(conceptPart);
            }

            String sanitizedIdentifier = UtilityMethods.sanitizeForIRI(trimmedIdentifier);
            return buildConceptURI(sanitizedIdentifier);
        }

        String sanitizedName = UtilityMethods.sanitizeForIRI(conceptName);
        return buildConceptURI(sanitizedName);
    }

    private String extractConceptName(String fullIRI) {
        int lastSlash = fullIRI.lastIndexOf('/');
        return lastSlash != -1 ? fullIRI.substring(lastSlash + 1) : fullIRI;
    }

    private String buildConceptURI(String sanitizedConceptName) {
        StringBuilder uriBuilder = new StringBuilder(effectiveNamespace);

        if (uriBuilder.toString().endsWith("/")) {
            uriBuilder.setLength(uriBuilder.length() - 1);
        }

        if (vocabularyName != null && !vocabularyName.trim().isEmpty()) {
            String sanitizedVocabName = UtilityMethods.sanitizeForIRI(vocabularyName);
            uriBuilder.append("/").append(sanitizedVocabName);
        }

        uriBuilder.append("/pojem/").append(sanitizedConceptName);

        return uriBuilder.toString();
    }

    public String generateVocabularyURI(String vocabularyName, String identifier) {
        if (identifier != null && !identifier.trim().isEmpty()) {
            String trimmedIdentifier = UtilityMethods.removeTrailingSlash(identifier.trim());

            if (UtilityMethods.isValidIRI(trimmedIdentifier)) {
                return trimmedIdentifier;
            }

            String sanitizedIdentifier = UtilityMethods.sanitizeForIRI(trimmedIdentifier);
            return buildVocabularyURI(sanitizedIdentifier);
        }

        if (vocabularyName == null || vocabularyName.trim().isEmpty()) {
            return effectiveNamespace;
        }

        String sanitizedName = UtilityMethods.sanitizeForIRI(vocabularyName);
        return buildVocabularyURI(sanitizedName);
    }

    public String generateVocabularyURIFromGivenNamespace(String vocabularyName, String namespace) {
        if (vocabularyName == null || vocabularyName.trim().isEmpty()) {
            return effectiveNamespace;
        }

        String sanitizedName = UtilityMethods.sanitizeForIRI(vocabularyName);
        return buildVocabularyURIWithGivenNamesapce(sanitizedName, namespace);
    }

    private String buildVocabularyURI(String sanitizedVocabularyName) {
        String baseNamespace = effectiveNamespace;
        if (baseNamespace.endsWith("/")) {
            baseNamespace = baseNamespace.substring(0, baseNamespace.length() - 1);
        }
        return baseNamespace + "/" + sanitizedVocabularyName;
    }

    private String buildVocabularyURIWithGivenNamesapce(String sanitizedVocabularyName, String namespace) {
        String baseNamespace = null;
        if (namespace == null || namespace.trim().isEmpty()) {
            baseNamespace = effectiveNamespace;
        } else {
            baseNamespace = namespace;
            if (baseNamespace.endsWith("/")) {
                baseNamespace = baseNamespace.substring(0, baseNamespace.length() - 1);
            }
        }

        return baseNamespace + "/" + sanitizedVocabularyName;
    }

    public String generateRuleIRI(String ruleName, boolean isLocal) {
        try {
            String sanitizedName = UtilityMethods.sanitizeForIRI(ruleName);

            sanitizedName = convertToKebabCase(sanitizedName);

            String baseUri = isLocal ? LOCAL_SHACL_BASE_URI : GLOBAL_SHACL_BASE_URI;
            return baseUri + sanitizedName;

        } catch (Exception e) {
            log.debug("Error generating rule IRI: {}", e.getMessage());
            return generateDefaultRuleIRI();
        }
    }

    private String convertToKebabCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return "unnamed-rule";
        }

        String result = camelCase.replaceAll("([a-z])([A-Z])", "$1-$2")
                .toLowerCase();

        result = result.replaceAll("-+", "-");

        result = result.replaceAll("^-+|-+$", "");

        return result.isEmpty() ? "unnamed-rule" : result;
    }

    public String generateDefaultRuleIRI() {
        return GLOBAL_SHACL_BASE_URI + "unknown-rule";
    }
}