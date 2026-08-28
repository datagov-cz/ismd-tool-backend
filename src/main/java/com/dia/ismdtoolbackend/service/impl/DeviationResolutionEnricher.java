package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.PropertyDataType;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.PropertyDeviation;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Attaches resolved navigation metadata to a {@link PublishedConceptDeviationModel} so the FE can render
 * and navigate its concept references (the {@code source} NKD twin, {@code definiční-obor}, concept-typed
 * {@code obor-hodnot}) and RPP references ({@code agenda}, {@code ais}) — on BOTH sides of every diff —
 * without a second resolve round-trip. Mirrors the resolved shapes the detail flow already emits.
 *
 * <p>Origin-agnostic: works the same for a WORKING_COPY deviation (own value vs NKD twin) and a
 * LINK_TARGET snapshot deviation (stored copy vs live NKD), so both surfaces share this one implementation.
 */
@Component
@RequiredArgsConstructor
public class DeviationResolutionEnricher {

    private final ReferencedConceptResolutionEngine resolutionEngine;
    private final RppSnapshotHolder rppSnapshotHolder;

    /** Enriches {@code deviation} in place; a null deviation is a no-op. */
    public void enrich(PublishedConceptDeviationModel deviation) {
        if (deviation == null) {
            return;
        }
        Set<String> conceptIris = new LinkedHashSet<>();
        collectInto(deviation, conceptIris);
        applyResolved(deviation, conceptIris.isEmpty()
                ? Map.of()
                : resolutionEngine.resolveAll(new ArrayList<>(conceptIris)));
        applyRppResolved(deviation);
    }

    /**
     * Bulk variant: resolves the concept references of EVERY deviation in one
     * {@link ReferencedConceptResolutionEngine#resolveAll} call, then enriches each from the shared
     * result.
     */
    public void enrichAll(List<PublishedConceptDeviationModel> deviations) {
        if (deviations == null || deviations.isEmpty()) {
            return;
        }
        // One pass to collect, one resolve, one pass to apply. resolveAll dedups and caches
        // internally, so the union is cheaper than the sum of the parts even when IRIs repeat.
        Set<String> allIris = new LinkedHashSet<>();
        for (PublishedConceptDeviationModel deviation : deviations) {
            if (deviation != null) {
                collectInto(deviation, allIris);
            }
        }

        Map<String, ResolvedConceptDto> resolved = allIris.isEmpty()
                ? Map.of()
                : resolutionEngine.resolveAll(new ArrayList<>(allIris));

        for (PublishedConceptDeviationModel deviation : deviations) {
            if (deviation != null) {
                applyResolved(deviation, resolved);
                applyRppResolved(deviation);
            }
        }
    }

    /**
     * Collects this deviation's concept IRIs into {@code sink} — the source twin, {@code definiční-obor}
     * and concept-typed {@code obor-hodnot}, both diff sides — and attaches any datatype ranges, which
     * resolve locally with no round-trip.
     */
    private void collectInto(PublishedConceptDeviationModel deviation, Set<String> sink) {
        if (deviation.getSource() != null) {
            addIri(sink, deviation.getSource().getIri());
        }
        addBothSides(sink, deviation.getDomain());

        // Range: datatype values resolve to DataTypeDto; anything else is a concept IRI → resolve as a concept.
        Map<String, DataTypeDto> rangeResolved = new LinkedHashMap<>();
        collectRange(deviation.getRange(), sink, rangeResolved);
        if (!rangeResolved.isEmpty()) {
            deviation.setRangeResolved(rangeResolved);
        }
    }

    /**
     * Attaches the subset of {@code resolved} this deviation actually references, so a deviation never
     * carries another's resolutions when the map is shared across a bulk run.
     */
    private void applyResolved(PublishedConceptDeviationModel deviation,
                               Map<String, ResolvedConceptDto> resolved) {
        if (resolved.isEmpty()) {
            return;
        }
        Set<String> own = new LinkedHashSet<>();
        if (deviation.getSource() != null) {
            addIri(own, deviation.getSource().getIri());
        }
        addBothSides(own, deviation.getDomain());
        collectRange(deviation.getRange(), own, new LinkedHashMap<>());

        Map<String, ResolvedConceptDto> mine = new LinkedHashMap<>();
        for (String iri : own) {
            ResolvedConceptDto dto = resolved.get(iri);
            if (dto != null) {
                mine.put(iri, dto);
            }
        }
        if (!mine.isEmpty()) {
            deviation.setReferencedConceptsResolved(mine);
        }
    }

    /** RPP agenda / ais, both diff sides. Served from the in-memory snapshot — no round-trip. */
    private void applyRppResolved(PublishedConceptDeviationModel deviation) {
        Map<String, RppAgenda> agendaResolved = new LinkedHashMap<>();
        forEachSide(deviation.getAgenda(), iri ->
                rppSnapshotHolder.findAgendaByIri(iri).ifPresent(a -> agendaResolved.put(iri, a)));
        if (!agendaResolved.isEmpty()) {
            deviation.setAgendaResolved(agendaResolved);
        }

        Map<String, RppIsvs> aisResolved = new LinkedHashMap<>();
        forEachSide(deviation.getAis(), iri ->
                rppSnapshotHolder.findIsvsByIri(iri).ifPresent(i -> aisResolved.put(iri, i)));
        if (!aisResolved.isEmpty()) {
            deviation.setAisResolved(aisResolved);
        }
    }

    /** A range value is a DataTypeDto when it's a known XSD datatype (VLASTNOST); otherwise a concept IRI. */
    private void collectRange(PropertyDeviation<String> range, Set<String> conceptIris,
                             Map<String, DataTypeDto> rangeResolved) {
        forEachSide(range, value -> {
            Optional<PropertyDataType> datatype = PropertyDataType.fromValue(value);
            if (datatype.isPresent()) {
                rangeResolved.put(value, datatype.get().toDto());
            } else {
                addIri(conceptIris, value);
            }
        });
    }

    /** Runs {@code action} for each non-blank side (local, published) of a string deviation. */
    private void forEachSide(PropertyDeviation<String> deviation, Consumer<String> action) {
        if (deviation == null) {
            return;
        }
        applyIfPresent(deviation.getLocalValue(), action);
        applyIfPresent(deviation.getPublishedValue(), action);
    }

    private void applyIfPresent(String value, Consumer<String> action) {
        if (value != null && !value.isBlank()) {
            action.accept(value);
        }
    }

    private void addBothSides(Set<String> sink, PropertyDeviation<String> deviation) {
        forEachSide(deviation, v -> addIri(sink, v));
    }

    private void addIri(Set<String> sink, String iri) {
        if (iri != null && !iri.isBlank()) {
            sink.add(iri);
        }
    }
}