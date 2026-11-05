package com.dia.ismdtoolbackend.utility;

import lombok.Getter;
import lombok.Setter;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.DEFAULT_NS;

/** Builds concept and vocabulary IRIs under an effective namespace. */
@Setter
@Getter
public class URIGenerator {
    private String effectiveNamespace = DEFAULT_NS;

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

        uriBuilder.append("/pojem/").append(sanitizedConceptName);

        return uriBuilder.toString();
    }

    public String generateVocabularyURIFromGivenNamespace(String vocabularyName, String namespace) {
        if (vocabularyName == null || vocabularyName.trim().isEmpty()) {
            return effectiveNamespace;
        }

        String baseNamespace = namespace == null || namespace.trim().isEmpty() ? effectiveNamespace : namespace;
        if (baseNamespace.endsWith("/")) {
            baseNamespace = baseNamespace.substring(0, baseNamespace.length() - 1);
        }
        return baseNamespace + "/" + UtilityMethods.sanitizeForIRI(vocabularyName);
    }
}
