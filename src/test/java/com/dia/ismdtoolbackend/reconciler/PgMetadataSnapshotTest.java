package com.dia.ismdtoolbackend.reconciler;

import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PgMetadataSnapshotTest {

    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private OntologyMetadataRepository ontologyMetadataRepository;

    private PgMetadataSnapshot snapshotWithCap(int cap) {
        ReconcilerConfig config = new ReconcilerConfig();
        config.setMaxConcepts(cap);
        return new PgMetadataSnapshot(conceptMetadataRepository, ontologyMetadataRepository, config);
    }

    @Test
    void overCap_abortsBeforeMaterializing() {
        when(conceptMetadataRepository.count()).thenReturn(101L);
        PgMetadataSnapshot snapshot = snapshotWithCap(100);

        IllegalStateException ex = assertThrows(IllegalStateException.class, snapshot::load);
        assertEquals(true, ex.getMessage().contains("max-concepts"));
        // The whole table must NOT be loaded once the cap is tripped.
        verify(conceptMetadataRepository, never()).findAll();
    }

    @Test
    void underCap_loadsNormally() {
        when(conceptMetadataRepository.count()).thenReturn(5L);
        when(conceptMetadataRepository.findAll()).thenReturn(List.of());
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of());
        PgMetadataSnapshot snapshot = snapshotWithCap(100);

        PgMetadataSnapshot.Snapshot snap = snapshot.load();

        assertEquals(0, snap.concepts().size());
        verify(conceptMetadataRepository).findAll();
    }

    @Test
    void capZero_disablesCheck_noCountCall() {
        when(conceptMetadataRepository.findAll()).thenReturn(List.of());
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of());
        PgMetadataSnapshot snapshot = snapshotWithCap(0);

        snapshot.load();

        verify(conceptMetadataRepository, never()).count();
        verify(conceptMetadataRepository).findAll();
    }
}
