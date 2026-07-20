package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.enums.SnapshotAction;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinkSnapshotAssemblerTest {

    private static final Duration TTL = Duration.ofHours(24);
    private static final Instant NOW = Instant.parse("2026-06-22T12:00:00Z");

    private NkdConceptSnapshotEntity snapshot(String ownerIri, Long ownerId, Instant lastChecked,
                                              DeviationStatus cachedStatus) {
        ConceptMetadataEntity owner = new ConceptMetadataEntity();
        owner.setId(ownerId);
        owner.setConceptIri(ownerIri);

        NkdConceptSnapshotEntity s = new NkdConceptSnapshotEntity();
        s.setId(ownerId * 10);
        s.setOwningConcept(owner);
        s.setNkdIri("https://slovník.gov.cz/agendový/104/pojem/adresní-místo");
        s.setOrigin(SnapshotOrigin.LINK_TARGET);
        s.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());
        s.setLastCheckedAt(lastChecked);
        s.setLastDeviationStatus(cachedStatus);
        s.setSnapshot(ConceptDetailModel.builder()
                .iri(s.getNkdIri())
                .name(Map.of("cs", "Adresní místo"))
                .build());
        return s;
    }

    @Test
    void fresh_hasDeviations_servesCachedStatusAndActions() {
        var rows = List.of(snapshot("https://x/pojem/a", 1L,
                NOW.minus(Duration.ofHours(1)), DeviationStatus.HAS_DEVIATIONS));

        var result = LinkSnapshotAssembler.assemble(rows, TTL, NOW);

        assertThat(result.needsWarming()).isFalse();
        LinkSnapshotDto dto = result.byOwnerConcept().get("https://x/pojem/a").get(0);
        assertThat(dto.getStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        assertThat(dto.getAvailableActions())
                .containsExactlyInAnyOrder(SnapshotAction.UPDATE, SnapshotAction.REMOVE);
        assertThat(dto.getNkdConcept().getLabel()).isEqualTo("Adresní místo");
        assertThat(dto.getLinkPredicate()).isEqualTo(SnapshotLinkType.BROADER_CLASS);
    }

    @Test
    void fresh_noDeviation_onlyRemove() {
        var rows = List.of(snapshot("https://x/pojem/a", 1L,
                NOW.minus(Duration.ofHours(1)), DeviationStatus.NO_DEVIATION));

        var dto = LinkSnapshotAssembler.assemble(rows, TTL, NOW).byOwnerConcept().get("https://x/pojem/a").get(0);

        assertThat(dto.getAvailableActions()).containsExactly(SnapshotAction.REMOVE);
    }

    @Test
    void stale_treatedAsPending_andNeedsWarming() {
        var rows = List.of(snapshot("https://x/pojem/a", 1L,
                NOW.minus(Duration.ofHours(30)), DeviationStatus.HAS_DEVIATIONS)); // older than 24h TTL

        var result = LinkSnapshotAssembler.assemble(rows, TTL, NOW);

        assertThat(result.needsWarming()).isTrue();
        LinkSnapshotDto dto = result.byOwnerConcept().get("https://x/pojem/a").get(0);
        assertThat(dto.getStatus()).isEqualTo(DeviationStatus.PENDING);
        assertThat(dto.getAvailableActions()).isEmpty();
        assertThat(dto.getDeviation()).isNull();
    }

    @Test
    void neverChecked_isPending_andNeedsWarming() {
        var rows = List.of(snapshot("https://x/pojem/a", 1L, null, null));

        var result = LinkSnapshotAssembler.assemble(rows, TTL, NOW);

        assertThat(result.needsWarming()).isTrue();
        assertThat(result.byOwnerConcept().get("https://x/pojem/a").get(0).getStatus())
                .isEqualTo(DeviationStatus.PENDING);
    }

    @Test
    void groupsByOwnerConcept() {
        var rows = List.of(
                snapshot("https://x/pojem/a", 1L, NOW.minus(Duration.ofHours(1)), DeviationStatus.NO_DEVIATION),
                snapshot("https://x/pojem/b", 2L, NOW.minus(Duration.ofHours(1)), DeviationStatus.NO_DEVIATION));

        var byOwner = LinkSnapshotAssembler.assemble(rows, TTL, NOW).byOwnerConcept();

        assertThat(byOwner).containsOnlyKeys("https://x/pojem/a", "https://x/pojem/b");
    }

    @Test
    void emptyRows_emptyMap_noWarming() {
        var result = LinkSnapshotAssembler.assemble(List.of(), TTL, NOW);
        assertThat(result.byOwnerConcept()).isEmpty();
        assertThat(result.needsWarming()).isFalse();
    }
}
