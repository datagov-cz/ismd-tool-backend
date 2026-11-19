package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.outbox.dto.OutboxEntryDto;
import com.dia.ismdtoolbackend.outbox.dto.OutboxStatusDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxAdminServiceImplTest {

    private static final Instant CREATED = Instant.parse("2026-10-21T08:00:00Z");

    @Mock
    private OutboxEntryRepository repository;
    @Mock
    private OutboxRelay relay;

    private OutboxAdminServiceImpl admin;

    @BeforeEach
    void setUp() {
        admin = new OutboxAdminServiceImpl(repository, relay);
    }

    @Test
    void statusCountsEveryStateAndTheOldestPendingRow() {
        when(repository.countByStatus(OutboxStatus.PENDING)).thenReturn(3L);
        when(repository.countByStatus(OutboxStatus.FAILED)).thenReturn(1L);
        when(repository.countByStatus(OutboxStatus.DONE)).thenReturn(40L);
        when(repository.oldestPendingCreatedAt()).thenReturn(CREATED);

        assertThat(admin.status()).isEqualTo(new OutboxStatusDto(3, 1, 40, CREATED));
    }

    @Test
    void failedListsRowsWithoutTheirPayloads() {
        OutboxEntry row = failedRow(7L);
        row.setInsertTriples("<a> <b> <c> .");
        when(repository.findByStatusOrderBySeqAsc(OutboxStatus.FAILED)).thenReturn(List.of(row));

        assertThat(admin.failed()).containsExactly(new OutboxEntryDto(
                7L, "UPSERT_CONCEPT", "https://slovník.gov.cz/g/pojem/a", "https://slovník.gov.cz/g",
                "FAILED", 5, "Fuseki down", CREATED, null, 70L));
    }

    @Test
    void failedRowWithoutOperationOrStatusIsListedWithNulls() {
        OutboxEntry row = failedRow(8L);
        row.setOperation(null);
        row.setStatus(null);
        when(repository.findByStatusOrderBySeqAsc(OutboxStatus.FAILED)).thenReturn(List.of(row));

        assertThat(admin.failed()).singleElement().satisfies(dto -> {
            assertThat(dto.operation()).isNull();
            assertThat(dto.status()).isNull();
            assertThat(dto.id()).isEqualTo(8L);
        });
    }

    @Test
    void drainNowReturnsTheRowsTheRelayApplied() {
        when(relay.drainOnce()).thenReturn(4);

        assertThat(admin.drainNow()).isEqualTo(4);
    }

    @Test
    void retryResetsAFailedRowToAFreshPendingOne() {
        OutboxEntry row = failedRow(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(row));

        assertThat(admin.retry(7L)).isTrue();

        verify(repository).save(row);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
    }

    @Test
    void retryOfAnUnknownRowChangesNothing() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThat(admin.retry(7L)).isFalse();
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = OutboxStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    void onlyFailedRowsCanBeRetried(OutboxStatus status) {
        OutboxEntry row = failedRow(7L);
        row.setStatus(status);
        when(repository.findById(7L)).thenReturn(Optional.of(row));

        assertThat(admin.retry(7L)).isFalse();

        verify(repository, never()).save(any());
        assertThat(row.getAttempts()).isEqualTo(5);
        assertThat(row.getLastError()).isEqualTo("Fuseki down");
    }

    private static OutboxEntry failedRow(long id) {
        OutboxEntry row = new OutboxEntry();
        row.setId(id);
        row.setOperation(OutboxOperation.UPSERT_CONCEPT);
        row.setAggregateIri("https://slovník.gov.cz/g/pojem/a");
        row.setGraphName("https://slovník.gov.cz/g");
        row.setStatus(OutboxStatus.FAILED);
        row.setAttempts(5);
        row.setLastError("Fuseki down");
        row.setCreatedAt(CREATED);
        row.setSeq(70L);
        return row;
    }
}
