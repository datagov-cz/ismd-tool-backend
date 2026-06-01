package com.dia.ismdtoolbackend.utility.security;

import java.util.Objects;

/**
 * An external SPARQL endpoint whose IRIs the application both reads and writes.
 * Each instance carries the canonical prefix and host that distinguish IRIs
 * belonging to this endpoint from arbitrary user input — so writers can validate
 * them and readers can split them into {@code (domain, relativePath)} for
 * forward-compatible storage.
 *
 * <p>Add a new endpoint by registering an instance in {@link SparqlEndpointRegistry}.
 * The structural validation ({@link #accepts(String)}) layers on top of
 * {@link SparqlIriValidator#isSafeHttpIri(String)}; both must pass.
 */
public final class KnownSparqlEndpoint {

    private final String name;
    private final String domain;
    private final String stripPrefix;
    private final String canonicalPrefix;

    /**
     * @param name             short human-readable identifier, surfaced in logs and exception
     *                         messages (e.g. {@code "esbirka"}).
     * @param domain           scheme + host without trailing slash (e.g.
     *                         {@code "https://opendata.eselpoint.gov.cz"}). Used as the
     *                         {@code domain} field in DTOs that ship a {@code (domain, relativePath)}
     *                         split.
     * @param stripPrefix      the portion subtracted from a canonical IRI to produce the
     *                         relative path used in DTOs. Often equal to {@code domain},
     *                         but for endpoints that prepend a fixed path before the
     *                         meaningful identifier (e.g. e-Sbírka's {@code /esel-esb}
     *                         hop before {@code /eli/...}) it includes that path. Must
     *                         start with {@code domain}.
     * @param canonicalPrefix  full prefix that every accepted IRI must start with
     *                         (e.g. {@code "https://opendata.eselpoint.gov.cz/esel-esb/eli/"}).
     *                         Must start with {@code stripPrefix}.
     */
    public KnownSparqlEndpoint(String name, String domain, String stripPrefix, String canonicalPrefix) {
        this.name = Objects.requireNonNull(name, "name");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.stripPrefix = Objects.requireNonNull(stripPrefix, "stripPrefix");
        this.canonicalPrefix = Objects.requireNonNull(canonicalPrefix, "canonicalPrefix");
        if (!stripPrefix.startsWith(domain)) {
            throw new IllegalArgumentException(
                    "stripPrefix must start with domain: " + stripPrefix + " vs " + domain);
        }
        if (!canonicalPrefix.startsWith(stripPrefix)) {
            throw new IllegalArgumentException(
                    "canonicalPrefix must start with stripPrefix: " + canonicalPrefix + " vs " + stripPrefix);
        }
    }

    public String name() {
        return name;
    }

    public String domain() {
        return domain;
    }

    /**
     * True when {@code iri} both passes the generic safe-IRI check and starts with
     * this endpoint's canonical prefix. Use this everywhere user-controlled IRIs
     * are about to be interpolated into a SPARQL query against this endpoint.
     */
    public boolean accepts(String iri) {
        return SparqlIriValidator.isSafeHttpIri(iri) && iri.startsWith(canonicalPrefix);
    }

    /**
     * Returns the portion of {@code iri} after the endpoint's strip prefix — the
     * path the FE/BE would carry as the relative identifier alongside a
     * separately-stored {@link #domain()}. Returns {@code null} for IRIs that
     * don't pass {@link #accepts}.
     */
    public String relativePath(String iri) {
        if (!accepts(iri)) {
            return null;
        }
        return iri.substring(stripPrefix.length());
    }
}
