package com.dia.ismdtoolbackend.utility.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for {@link KnownSparqlEndpoint} instances. Spring-injectable
 * for new code, also exposed via a static accessor on {@link SparqlIriValidator} so
 * existing static call sites (writers, query builders, controllers) need not change
 * their wiring.
 *
 * <p>Adding a new endpoint means appending one line to {@link #registerAll()} —
 * everything else (validation in writers, DTO domain-split, security checks) becomes
 * automatic.
 */
@Component
public class SparqlEndpointRegistry {

    public static final String ESBIRKA = "esbirka";

    private final Map<String, KnownSparqlEndpoint> byName = new LinkedHashMap<>();

    public SparqlEndpointRegistry() {
        registerAll();
    }

    private void registerAll() {
        // e-Sbírka: domain is just scheme+host (used as the DTO `domain` field), but
        // the IRI has an extra `/esel-esb` hop before the meaningful `/eli/...` path,
        // so stripPrefix includes that hop while canonicalPrefix locks in `/eli/`.
        register(new KnownSparqlEndpoint(
                ESBIRKA,
                "https://opendata.eselpoint.gov.cz",
                "https://opendata.eselpoint.gov.cz/esel-esb",
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/"));
    }

    private void register(KnownSparqlEndpoint endpoint) {
        byName.put(endpoint.name(), endpoint);
    }

    /**
     * Looks up an endpoint by its registered name. Use the {@code ESBIRKA} (etc.)
     * constants rather than string literals to keep the call site searchable.
     */
    public KnownSparqlEndpoint require(String name) {
        KnownSparqlEndpoint endpoint = byName.get(name);
        if (endpoint == null) {
            throw new IllegalStateException("Unknown SPARQL endpoint: " + name);
        }
        return endpoint;
    }

    public Optional<KnownSparqlEndpoint> findByName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Returns the registered endpoint that {@link KnownSparqlEndpoint#accepts(String) accepts}
     * the given IRI, if any. Useful for code that needs to decide which endpoint an
     * unknown IRI belongs to without hard-coding a name.
     */
    public Optional<KnownSparqlEndpoint> findOwnerOf(String iri) {
        return byName.values().stream().filter(e -> e.accepts(iri)).findFirst();
    }

    public Collection<KnownSparqlEndpoint> all() {
        return byName.values();
    }

    /**
     * Wires this registry into the static {@link SparqlIriValidator} facade so existing
     * static call sites continue to work without taking a dependency on Spring.
     * Spring guarantees a single instance per context, so the static handoff is safe.
     */
    @PostConstruct
    void publishToStaticFacade() {
        SparqlIriValidator.setRegistry(this);
    }
}
