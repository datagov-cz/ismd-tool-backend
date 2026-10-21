package com.dia.ismdtoolbackend.reconciler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconcilerRunServiceTest {

    @Mock
    private ConsistencyReconciler reconciler;

    private ReconcilerRunService runService;

    @BeforeEach
    void setUp() {
        runService = new ReconcilerRunService(reconciler);
    }

    @Test
    void noReportBeforeTheFirstRun() {
        assertThat(runService.getLastReport()).isNull();
    }

    @Test
    void completedRunBecomesTheLastReport() {
        ReconciliationReport report = mock(ReconciliationReport.class);
        when(reconciler.reconcile("ADMIN")).thenReturn(report);

        assertThat(runService.run("ADMIN")).isSameAs(report);
        assertThat(runService.getLastReport()).isSameAs(report);
    }

    @Test
    void runSkippedBecauseOneIsInProgressKeepsThePreviousReport() {
        ReconciliationReport previous = mock(ReconciliationReport.class);
        when(reconciler.reconcile("ADMIN")).thenReturn(previous, (ReconciliationReport) null);
        runService.run("ADMIN");

        assertThat(runService.run("ADMIN")).isNull();
        assertThat(runService.getLastReport()).isSameAs(previous);
    }

    @Nested
    class Schedule {

        private final ReconcilerConfig config = new ReconcilerConfig();
        private ReconciliationScheduler scheduler;

        @BeforeEach
        void setUp() {
            scheduler = new ReconciliationScheduler(config, runService);
        }

        @Test
        void disabledReconcilerDoesNotRun() {
            config.setEnabled(false);

            scheduler.runScheduled();

            verifyNoInteractions(reconciler);
        }

        @Test
        void enabledReconcilerRunsAsScheduled() {
            config.setEnabled(true);
            ReconciliationReport report = mock(ReconciliationReport.class);
            when(reconciler.reconcile("SCHEDULED")).thenReturn(report);

            scheduler.runScheduled();

            assertThat(runService.getLastReport()).isSameAs(report);
        }

        @Test
        void runAlreadyInProgressIsSkippedQuietly() {
            config.setEnabled(true);
            when(reconciler.reconcile("SCHEDULED")).thenReturn(null);

            assertThatCode(scheduler::runScheduled).doesNotThrowAnyException();
            verify(reconciler).reconcile("SCHEDULED");
        }

        @Test
        void failedRunDoesNotEscapeTheSchedulerThread() {
            config.setEnabled(true);
            when(reconciler.reconcile("SCHEDULED")).thenThrow(new IllegalStateException("Fuseki down"));

            assertThatCode(scheduler::runScheduled).doesNotThrowAnyException();
            assertThat(runService.getLastReport()).isNull();
        }
    }
}
