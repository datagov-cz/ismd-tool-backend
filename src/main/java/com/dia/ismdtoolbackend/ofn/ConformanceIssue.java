package com.dia.ismdtoolbackend.ofn;

/**
 * Something the conformance gate removed from a vocabulary document, and why.
 *
 * @param kind       what was removed
 * @param conceptIri IRI of the affected concept, or null for the vocabulary itself
 * @param location   path in the JSON-LD document, e.g. {@code pojmy[řidič]/agenda}
 * @param message    human-readable reason (Czech, shown to users)
 * @param value      the removed value (an IRI or a literal's text), or null when a whole field or a
 *                   structured value was removed
 */
public record ConformanceIssue(Kind kind, String conceptIri, String location, String message, String value) {

    public ConformanceIssue(Kind kind, String conceptIri, String location, String message) {
        this(kind, conceptIri, location, message, null);
    }

    /** The document key the issue is about: the first field below the concept, e.g. {@code agenda}. */
    public String key() {
        String path = location;
        int conceptEnd = path.indexOf("]/");
        if (path.startsWith(OfnSpecification.CONCEPTS_KEY + "[") && conceptEnd > 0) {
            path = path.substring(conceptEnd + 2);
        }
        int end = path.length();
        for (char stop : new char[]{'/', '['}) {
            int index = path.indexOf(stop);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return path.substring(0, end);
    }

    public enum Kind {
        /** A value with no matching term or shape in the OFN context was dropped. */
        VALUE_DROPPED,
        /** A concept failed the OFN JSON Schema and was left out of the output. */
        CONCEPT_EXCLUDED,
        /** A reference to an excluded concept was removed. */
        REFERENCE_REMOVED,
        /** The vocabulary itself fails the OFN JSON Schema. */
        VOCABULARY_INVALID
    }
}
