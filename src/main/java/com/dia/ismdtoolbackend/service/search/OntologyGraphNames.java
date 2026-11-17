package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Every ontology's graph name, the scope of an ISMD search. Read on every search and changed only by an
 * ontology create, rename, delete or upload, which evict {@link #CACHE_NAME}.
 */
@Component
@RequiredArgsConstructor
public class OntologyGraphNames {

    public static final String CACHE_NAME = "ontologyGraphNames";

    private final OntologyMetadataRepository ontologyMetadataRepository;

    @Cacheable(cacheNames = CACHE_NAME, key = "'all'", sync = true)
    public List<String> all() {
        return ontologyMetadataRepository.findAllGraphNames().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
