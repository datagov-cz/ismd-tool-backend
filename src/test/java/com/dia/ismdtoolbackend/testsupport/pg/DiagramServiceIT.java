package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceBeans;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptResolutionEngine;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The real {@code DiagramServiceImpl} and its focused classes against Postgres, with the RDF side
 * mocked. Every class carrying this annotation shares one Spring context.
 *
 * <p>The mocks are {@code @MockitoBean}s, reset after every test: autowire them by type and stub
 * them per test. {@link DiagramPendingEditRepository} is a spy on the real repository.
 * {@code DiagramMaterializeService} is a mock here; {@code DiagramMaterializeIntegrationTest}
 * covers the applying half against real RDF. No ambient test transaction: each service call
 * commits on its own boundary, as behind a request.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramServiceBeans.class, DiagramServiceTestBeans.class, TransactionTemplateConfig.class,
        JpaAuditingConfig.class, PgTestData.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class})
@MockitoBean(types = {JenaTDB2Repository.class, OntologyDetailExtractor.class, OntologyLabelLookup.class,
        DiagramMaterializeService.class, ReferencedConceptResolutionEngine.class})
@MockitoSpyBean(types = DiagramPendingEditRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public @interface DiagramServiceIT {
}
