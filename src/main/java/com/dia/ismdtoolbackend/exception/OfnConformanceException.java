package com.dia.ismdtoolbackend.exception;

import com.dia.ismdtoolbackend.ofn.ConformanceIssue;

import java.util.List;

/**
 * Thrown when data does not conform to OFN slovníky. Nothing is written or downloaded; the issues say
 * what to correct.
 */
public class OfnConformanceException extends RuntimeException {

    /** Where the check failed; decides the error code. */
    public enum Context {
        /** Form, diagram or ontology write: a safety net that cannot fail by construction. */
        WRITE,
        /** An upload whose vocabulary itself does not conform. */
        UPLOAD,
        /** A download of a vocabulary that is not complete and conformant. */
        DOWNLOAD
    }

    private final transient Context context;
    private final transient String vocabularyIri;
    private final transient List<ConformanceIssue> issues;

    public OfnConformanceException(Context context, String vocabularyIri, List<ConformanceIssue> issues) {
        super(message(context, issues.size()));
        this.context = context;
        this.vocabularyIri = vocabularyIri;
        this.issues = List.copyOf(issues);
    }

    private static String message(Context context, int count) {
        String found = count + " " + pluralize(count);
        return switch (context) {
            case WRITE -> "Zápis by uložil data nesplňující standard OFN Slovníky (" + found + ").";
            case UPLOAD -> "Nahraný slovník nesplňuje standard OFN Slovníky (" + found + ").";
            case DOWNLOAD -> "Slovník nelze stáhnout, protože nesplňuje standard OFN Slovníky (" + found
                    + "). Opravte uvedené pojmy a stáhněte ho znovu.";
        };
    }

    /** Czech needs three forms: 1 problém, 2-4 problémy, 0 or 5+ problémů. */
    private static String pluralize(int count) {
        if (count == 1) {
            return "problém";
        }
        return count >= 2 && count <= 4 ? "problémy" : "problémů";
    }

    public Context getContext() {
        return context;
    }

    public String getVocabularyIri() {
        return vocabularyIri;
    }

    public List<ConformanceIssue> getIssues() {
        return issues;
    }
}
