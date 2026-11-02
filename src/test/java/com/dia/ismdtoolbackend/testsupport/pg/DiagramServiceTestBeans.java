package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.impl.DiagramConceptUsageService;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptResolutionEngine;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** The real diagram collaborators that {@link DiagramServiceIT} wires around the mocked RDF side. */
@TestConfiguration
public class DiagramServiceTestBeans {

    @Bean
    DiagramMapper diagramMapper() {
        return new DiagramMapper();
    }

    @Bean
    DiagramLayoutReconciler diagramLayoutReconciler(DiagramMapper mapper, ConceptMetadataRepository conceptRepository,
                                                    DiagramPendingEditRepository pendingEditRepository) {
        return new DiagramLayoutReconciler(mapper, conceptRepository, pendingEditRepository);
    }

    @Bean
    DiagramConceptUsageService diagramConceptUsageService(DiagramRepository diagramRepository,
                                                          DiagramPendingEditRepository pendingEditRepository,
                                                          ConceptMetadataRepository conceptRepository,
                                                          JenaTDB2Repository jenaTDB2Repository,
                                                          ReferencedConceptResolutionEngine resolutionEngine) {
        return new DiagramConceptUsageService(diagramRepository, pendingEditRepository, conceptRepository,
                jenaTDB2Repository, resolutionEngine);
    }
}
