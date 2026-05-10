package com.dia.ismdtoolbackend.utility.security;

import org.apache.jena.irix.IRIException;
import org.apache.jena.irix.IRIx;

/**
 * Static facade over the SPARQL IRI safety checks. Two responsibilities:
 * <ul>
 *   <li>{@link #isSafeHttpIri(String)} — endpoint-agnostic guard against IRIs that
 *       could break out of {@code <...>} and inject SPARQL. Used wherever a
 *       caller-supplied IRI is about to enter a query, regardless of which
 *       endpoint that query targets.</li>
 *   <li>{@link #isEsbirkaEliIri(String)}, {@link #extractEsbirkaEliPath(String)},
 *       {@link #esbirkaDomain()} — Esbirka-specific shortcuts that delegate to
 *       the {@link SparqlEndpointRegistry}. The registry is the source of truth;
 *       these methods exist so existing static call sites (writers, query
 *       builders, tests) don't need to reach for Spring injection.</li>
 * </ul>
 *
 * <p>The registry is wired in by {@link SparqlEndpointRegistry#publishToStaticFacade()}
 * during Spring startup. Calls before that point fall through to a baked-in
 * Esbirka definition so unit tests and other static-only callers work without
 * spinning up an application context.
 */
public final class SparqlIriValidator {

    /** Used as a fallback when {@link #setRegistry} hasn't fired yet (unit tests, static contexts). */
    private static final KnownSparqlEndpoint ESBIRKA_FALLBACK = new KnownSparqlEndpoint(
            SparqlEndpointRegistry.ESBIRKA,
            "https://opendata.eselpoint.gov.cz",
            "https://opendata.eselpoint.gov.cz/esel-esb",
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/");

    private static volatile SparqlEndpointRegistry registry;

    private SparqlIriValidator() {
    }

    /**
     * Called by {@link SparqlEndpointRegistry#publishToStaticFacade()} during Spring
     * startup. Tests don't need to call this — the {@link #ESBIRKA_FALLBACK} handles
     * Esbirka before the registry is published.
     */
    public static void setRegistry(SparqlEndpointRegistry value) {
        registry = value;
    }

    private static KnownSparqlEndpoint esbirka() {
        SparqlEndpointRegistry r = registry;
        if (r == null) {
            return ESBIRKA_FALLBACK;
        }
        return r.findByName(SparqlEndpointRegistry.ESBIRKA).orElse(ESBIRKA_FALLBACK);
    }

    /**
     * Validates that an IRI is a safe e-Sbírka ELI reference: it must pass
     * {@link #isSafeHttpIri(String)} and start with the canonical e-Sbírka host
     * + {@code /esel-esb/eli/} prefix.
     */
    public static boolean isEsbirkaEliIri(String iri) {
        return esbirka().accepts(iri);
    }

    /**
     * Returns the {@code /eli/...} slice of a canonical e-Sbírka IRI (the
     * portion that survives a future split into {@code (domain, eliPath)}
     * storage). Returns {@code null} for IRIs that don't match the canonical
     * prefix.
     */
    public static String extractEsbirkaEliPath(String iri) {
        return esbirka().relativePath(iri);
    }

    /**
     * Returns the canonical e-Sbírka domain (scheme + host) — suitable for
     * the {@code domain} field of {@code LawDto}.
     */
    public static String esbirkaDomain() {
        return esbirka().domain();
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
