package com.dia.ismdtoolbackend.testsupport.context;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application context on H2, with Keycloak settings and every outside endpoint switched off
 * ({@code testsupport/full-context.properties}).
 *
 * <p>The class picks the profile with {@code @ActiveProfiles} and names its own in-memory database with
 * {@code @TestPropertySource(properties = "full-context.db-name=...")}; that property is required.
 * Properties declared directly on the class override the shared file.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@TestPropertySource(locations = "classpath:testsupport/full-context.properties")
public @interface FullContextTest {
}
