package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base for {@link OutboxFlowIT} classes. The context is shared, so the mutable singletons are put
 * back before every test: all {@link OutboxConfig} fields to their defaults and an empty
 * {@link InMemoryTdb2}.
 */
public abstract class OutboxFlowTestBase extends PostgresIntegrationTestBase {

    @Autowired
    protected OutboxConfig outboxConfig;

    @Autowired
    protected InMemoryTdb2 tdb2;

    @BeforeAll
    static void initJena() {
        // Static Jena init before any vocabulary use, so the test classloader cannot half-initialise it.
        JenaSystem.init();
    }

    @BeforeEach
    protected final void resetOutboxSingletons() {
        BeanUtils.copyProperties(new OutboxConfig(), outboxConfig);
        tdb2.reset();
    }
}
