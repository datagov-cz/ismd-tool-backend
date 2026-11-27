package com.dia.ismdtoolbackend.publication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped {@code application.properties} declares every {@code ismd.publication.*} property with the
 * class defaults, and each one is overridable per environment. The dev, test and local profiles point
 * {@code context-url} at the repository's {@code pages/} file through jsDelivr until GitHub Pages is enabled.
 */
class PublicationPropertiesTest {

    private static final String JSDELIVR = "https://cdn.jsdelivr.net/gh/datagov-cz/ismd-tool-backend@";
    private static final Path DRAFT_CONTEXT_FILE = Path.of("pages", "ismd-otevřená-data", "draft", "kontext.jsonld");

    private final ApplicationContextRunner runner = runner("application.properties");

    /** Binds {@code PublicationConfig} from the given classpath files, the first taking precedence. */
    private static ApplicationContextRunner runner(String... files) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(PublicationConfig.class)
                .withInitializer(context -> {
                    for (String file : files) {
                        try {
                            new PropertiesPropertySourceLoader().load(file, new ClassPathResource(file))
                                    .forEach(context.getEnvironment().getPropertySources()::addLast);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
    }

    @Test
    void shippedProperties_matchClassDefaults() {
        PublicationConfig defaults = new PublicationConfig();
        runner.run(context -> {
            PublicationConfig config = context.getBean(PublicationConfig.class);
            assertThat(config.isIncludeDrafts()).isEqualTo(defaults.isIncludeDrafts()).isTrue();
            assertThat(config.getRetainFiles()).isEqualTo(defaults.getRetainFiles()).isEqualTo(30);
            assertThat(config.getDownloadParallelism()).isEqualTo(defaults.getDownloadParallelism()).isEqualTo(4);
            assertThat(config.getIriBase()).isEqualTo(defaults.getIriBase());
            assertThat(config.getContextUrl()).isEqualTo(PublicationConfig.DRAFT_CONTEXT_URL);
        });
    }

    @Test
    void everyProperty_isOverridablePerEnvironment() {
        runner.withPropertyValues(
                        "PUBLICATION_INCLUDE_DRAFTS=false",
                        "PUBLICATION_RETAIN_FILES=5",
                        "PUBLICATION_DOWNLOAD_PARALLELISM=8",
                        "PUBLICATION_IRI_BASE=https://example.org/ismd/",
                        "PUBLICATION_CONTEXT_URL=https://example.org/2026-11-19/kontext.jsonld")
                .run(context -> {
                    PublicationConfig config = context.getBean(PublicationConfig.class);
                    assertThat(config.isIncludeDrafts()).isFalse();
                    assertThat(config.getRetainFiles()).isEqualTo(5);
                    assertThat(config.getDownloadParallelism()).isEqualTo(8);
                    assertThat(config.getIriBase()).isEqualTo("https://example.org/ismd/");
                    assertThat(config.getContextUrl()).isEqualTo("https://example.org/2026-11-19/kontext.jsonld");
                });
    }

    @Test
    void downloadParallelism_isAtLeastOne() {
        runner.withPropertyValues("PUBLICATION_DOWNLOAD_PARALLELISM=0")
                .run(context -> assertThat(context.getBean(PublicationConfig.class).getDownloadParallelism())
                        .isEqualTo(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "local"})
    void nonProductionProfiles_serveTheDraftContextThroughJsDelivr(String profile) {
        runner("application-" + profile + ".properties", "application.properties").run(context -> {
            String url = context.getBean(PublicationConfig.class).getContextUrl();
            assertThat(url).startsWith(JSDELIVR).doesNotContain("Ã");
            String path = url.substring(url.indexOf('/', JSDELIVR.length()) + 1);
            assertThat(Path.of(path)).as("the commit-pinned path is the draft context file")
                    .isEqualTo(DRAFT_CONTEXT_FILE).isRegularFile();
        });
    }
}
