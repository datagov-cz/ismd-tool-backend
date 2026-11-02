package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The real outbox and concept service against Postgres and an in-memory TDB2
 * ({@link OutboxFlowTestBeans}). Every class carrying this annotation and extending
 * {@link OutboxFlowTestBase} shares one Spring context.
 *
 * <p>No ambient test transaction by default: the service and relay open their own transactions, so
 * the after-commit nudge fires on a real commit. A class or method may opt into the rolled-back test
 * transaction with a plain {@code @Transactional}; on a class this keeps the shared context, because
 * {@code @DataJpaTest} already carries that same annotation (see {@link PlainJpaIT}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({OutboxFlowTestBeans.class, TransactionTemplateConfig.class, JpaAuditingConfig.class, PgTestData.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public @interface OutboxFlowIT {
}
