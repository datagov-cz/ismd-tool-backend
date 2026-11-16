package com.dia.ismdtoolbackend.publication;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicationSchedulerTest {

    private final PublicationConfig config = new PublicationConfig();
    private final PublicationService service = mock(PublicationService.class);
    private final PublicationScheduler scheduler = new PublicationScheduler(config, service);

    @Test
    void disabled_neverPublishes() {
        scheduler.publishOnStartupIfDue();
        scheduler.publishScheduledIfDue();

        verifyNoInteractions(service);
    }

    @Test
    void enabled_publishesOnlyWhenDue_onEitherTrigger() {
        config.setEnabled(true);

        scheduler.publishOnStartupIfDue();
        scheduler.publishScheduledIfDue();

        verify(service, org.mockito.Mockito.times(2)).publishIfDue();
    }

    @Test
    void aFailingRun_doesNotEscapeTheSchedulerThread() {
        config.setEnabled(true);
        when(service.publishIfDue()).thenThrow(new IllegalStateException("boom"));

        assertThatCode(scheduler::publishScheduledIfDue).doesNotThrowAnyException();
    }
}
