package com.dia.ismdtoolbackend.testsupport.web;

import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code @WebMvcTest} slice for one or more controllers under the {@code junit} profile.
 *
 * <p>JPA and OAuth2 auto-configuration are off. {@link TestSecurityConfig} permits every request and
 * keeps {@code @PreAuthorize} active; authenticate with {@code @WithMockSecurityUser}. The
 * {@code ontologySecurityService} bean is a {@link StubOntologySecurityService} that allows every
 * ownership check and is reset before each test; deny with {@link StubOntologySecurityService#denyModify()}.
 *
 * <p>{@code @RestControllerAdvice} beans such as {@code GlobalExceptionHandler} are part of the slice already.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest(excludeAutoConfiguration = {
        DataJpaRepositoriesAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
})
@Import({TestSecurityConfig.class, StubOntologySecurityService.Config.class})
@ActiveProfiles("junit")
@ExtendWith(StubOntologySecurityService.ResetBeforeEach.class)
public @interface ControllerSliceTest {

    /** The controllers to load; part of the context cache key. */
    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] controllers() default {};
}
