package com.dia.ismdtoolbackend.utility.security;

import org.apache.jena.irix.IRIException;
import org.apache.jena.irix.IRIx;

public final class SparqlIriValidator {

    private static final String ESBIRKA_ELI_PREFIX = "https://opendata.eselpoint.gov.cz/esel-esb/eli/";

    private SparqlIriValidator() {
    }

    /**
     * Validates that an IRI is a safe e-Sbírka ELI reference: it must pass
     * {@link #isSafeHttpIri(String)} and start with the canonical e-Sbírka host
     * + {@code /esel-esb/eli/} prefix.
     */
    public static boolean isEsbirkaEliIri(String iri) {
        return isSafeHttpIri(iri) && iri.startsWith(ESBIRKA_ELI_PREFIX);
    }

    /**
     * Validates that an IRI is safe to interpolate into a SPARQL {@code <...>}
     * reference. Rejects relative IRIs, non-http(s) schemes, and any character
     * that could close the {@code <...>} reference and inject arbitrary SPARQL.
     */
    public static boolean isSafeHttpIri(String iri) {
        if (iri == null || iri.isBlank()) {
            return false;
        }
        String scheme;
        try {
            IRIx parsed = IRIx.create(iri);
            if (!parsed.isReference()) {
                return false;
            }
            scheme = parsed.scheme();
        } catch (IRIException e) {
            return false;
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c <= 0x20 || c == '<' || c == '>' || c == '"' || c == '{' || c == '}'
                    || c == '|' || c == '^' || c == '`' || c == '\\') {
                return false;
            }
        }
        return true;
    }
}
