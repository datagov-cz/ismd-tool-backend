package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.mapper.CommentMapperImpl;
import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.service.impl.CommentServiceImpl;
import com.dia.ismdtoolbackend.service.search.DiagramSearchLookup;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Repositories and small JPA-only services against Postgres, with JPA auditing on as in production.
 * Every class carrying this annotation shares one Spring context.
 *
 * <p>The concept, pending-edit and diagram repositories are spies on the real repositories, so a
 * test can count queries or observe the calling transaction; Spring resets them after every test.
 *
 * <p>No ambient test transaction by default: writes commit. A class that wants the rolled-back test
 * transaction adds a plain {@code @Transactional}. That keeps the shared context: the cache key
 * includes the class's annotations, and {@code @DataJpaTest} already carries a plain
 * {@code @Transactional}, whereas any other value (e.g. a class-level {@code NOT_SUPPORTED}
 * without this default) would start a context of its own.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({JpaAuditingConfig.class, TransactionTemplateConfig.class, PgTestData.class, DiagramSearchLookup.class,
        CommentServiceImpl.class, CommentMapperImpl.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class})
@MockitoSpyBean(types = {ConceptMetadataRepository.class, DiagramPendingEditRepository.class, DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public @interface PlainJpaIT {
}
