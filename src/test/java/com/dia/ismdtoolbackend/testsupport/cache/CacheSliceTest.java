package com.dia.ismdtoolbackend.testsupport.cache;

import com.dia.ismdtoolbackend.config.CacheConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Spring context with the production {@link CacheConfig} plus the listed beans, for tests that
 * exercise {@code @Cacheable} through the real proxy. Classes listing the same beans and the same
 * {@code @MockitoBean}s share one cached context; call {@link CacheTestSupport#clearAll} in
 * {@code @BeforeEach}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@Import(CacheConfig.class)
public @interface CacheSliceTest {

    /** The beans under test and their real collaborators. */
    @AliasFor(annotation = SpringBootTest.class, attribute = "classes")
    Class<?>[] value();
}
