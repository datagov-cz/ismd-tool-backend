package com.dia.ismdtoolbackend.config.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for MtlsConfig - validates configuration structure.
 * Note: Full integration test with keystore loading requires actual certificate file
 * and running Spring context.
 */
class MtlsConfigTest {

    @Test
    void mtlsConfig_canBeInstantiated() {
        // Test that MtlsConfig class can be instantiated
        MtlsConfig config = new MtlsConfig();
        assertThat(config).isNotNull();
    }

    @Test
    void mtlsConfig_hasExpectedAnnotations() {
        // Test that MtlsConfig has @Configuration annotation
        assertThat(MtlsConfig.class.isAnnotationPresent(org.springframework.context.annotation.Configuration.class))
                .isTrue();
    }
}
