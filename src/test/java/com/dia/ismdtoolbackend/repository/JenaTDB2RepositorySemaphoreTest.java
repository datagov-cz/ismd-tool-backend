package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.config.FusekiConnectionConfig;
import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.net.http.HttpClient;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for JenaTDB2Repository semaphore timeout and input validation behavior.
 */
@ExtendWith(MockitoExtension.class)
class JenaTDB2RepositorySemaphoreTest {

    private static final String UNREACHABLE_ENDPOINT = "http://localhost:9999/nonexistent";

    private JenaTDB2Repository repository;
    private Semaphore semaphore;

    @BeforeEach
    void setUp() {
        semaphore = new Semaphore(10);
        repository = new JenaTDB2Repository(
                new FusekiConnectionConfig(HttpClient.newHttpClient(), semaphore, 50, UNREACHABLE_ENDPOINT),
                new MockEnvironment());
    }

    @Test
    void conceptNotFoundInGraph_nullUri_shouldReturnTrue() {
        boolean result = repository.conceptNotFoundInGraph(null, "urn:graph:test");
        assertThat(result).isTrue();
    }

    @Test
    void conceptNotFoundInGraph_emptyUri_shouldReturnTrue() {
        boolean result = repository.conceptNotFoundInGraph("", "urn:graph:test");
        assertThat(result).isTrue();
    }

    @Test
    void conceptNotFoundInGraph_blankUri_shouldReturnTrue() {
        boolean result = repository.conceptNotFoundInGraph("   ", "urn:graph:test");
        assertThat(result).isTrue();
    }

    @Test
    void deleteConceptsFromGraph_nullList_shouldThrowIllegalArgument() {
        assertThatThrownBy(() -> repository.deleteConceptsFromGraph(null, "urn:graph:test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteConceptsFromGraph_emptyList_shouldThrowIllegalArgument() {
        assertThatThrownBy(() -> repository.deleteConceptsFromGraph(java.util.List.of(), "urn:graph:test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void semaphoreExhausted_shouldThrowWithBusyMessage() {
        semaphore.drainPermits();

        JenaTDB2Exception thrown = assertThatExceptionOfType(JenaTDB2Exception.class)
                .isThrownBy(() -> repository.conceptNotFoundInGraph("urn:concept:1", "urn:graph:test"))
                .actual();

        assertThat(thrown).hasMessageContaining("busy");
    }

    @Test
    void semaphoreInterrupted_shouldThrowAndPreserveInterruptFlag() {
        // Use a long timeout and drain permits so tryAcquire blocks
        JenaTDB2Repository longTimeoutRepo = new JenaTDB2Repository(
                new FusekiConnectionConfig(HttpClient.newHttpClient(), new Semaphore(0), 10000, UNREACHABLE_ENDPOINT),
                new MockEnvironment());

        Thread.currentThread().interrupt();
        try {
            JenaTDB2Exception thrown = assertThatExceptionOfType(JenaTDB2Exception.class)
                    .isThrownBy(() -> longTimeoutRepo.conceptNotFoundInGraph("urn:concept:1", "urn:graph:test"))
                    .actual();

            assertThat(thrown).hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt flag is restored for the caller")
                    .isTrue();
        } finally {
            // Surefire runs every class on this thread; a flag left set breaks the next blocking call.
            Thread.interrupted();
        }
    }
}
