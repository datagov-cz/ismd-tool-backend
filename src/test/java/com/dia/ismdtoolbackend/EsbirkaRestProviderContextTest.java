package com.dia.ismdtoolbackend;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code esbirka.provider} wires exactly one {@link EsbirkaClient}, and the REST provider its deadline filter. */
class EsbirkaRestProviderContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EsbirkaClients.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean("externalSparqlHttpClient", HttpClient.class, HttpClient::newHttpClient);

    @Test
    void restProviderWiresOnlyTheRestClient() {
        runner.withPropertyValues("esbirka.provider=rest", "esbirka.rest.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EsbirkaClient.class).getClass().getSimpleName())
                            .isEqualTo("EsbirkaRestClient");
                    assertThat(context).doesNotHaveBean(EsbirkaSparqlClient.class);
                    assertThat(context).hasBean("esbirkaDeadlineFilter");
                });
    }

    @Test
    void missingProviderWiresOnlyTheSparqlClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(EsbirkaClient.class).isInstanceOf(EsbirkaSparqlClient.class);
            assertThat(context).doesNotHaveBean("esbirkaDeadlineFilter");
            assertThat(context).doesNotHaveBean("esbirkaRestTransport");
        });
    }

    @Test
    void sparqlProviderWiresOnlyTheSparqlClient() {
        runner.withPropertyValues("esbirka.provider=sparql").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(EsbirkaClient.class).isInstanceOf(EsbirkaSparqlClient.class);
            assertThat(context).doesNotHaveBean("esbirkaDeadlineFilter");
        });
    }

    /**
     * Both providers' beans, each behind its {@code @ConditionalOnProperty}. Configuration classes are
     * skipped: test configurations in the same package would otherwise be scanned too.
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.dia.ismdtoolbackend.client.esbirka",
            excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Configuration.class))
    @Import({EsbirkaConfig.class, EsbirkaSparqlClient.class})
    static class EsbirkaClients {
    }
}
