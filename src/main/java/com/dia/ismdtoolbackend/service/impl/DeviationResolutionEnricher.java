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

        // Concept IRIs: the source twin + domain + concept-typed range, both diff sides. One batch resolve.
        Set<String> conceptIris = new LinkedHashSet<>();
        if (deviation.getSource() != null) {
            addIri(conceptIris, deviation.getSource().getIri());
        }
        addBothSides(conceptIris, deviation.getDomain());

        // Range: datatype values resolve to DataTypeDto; anything else is a concept IRI → resolve as a concept.
        Map<String, DataTypeDto> rangeResolved = new LinkedHashMap<>();
        collectRange(deviation.getRange(), conceptIris, rangeResolved);
        if (!rangeResolved.isEmpty()) {
            deviation.setRangeResolved(rangeResolved);
        }

        if (!conceptIris.isEmpty()) {
            Map<String, ResolvedConceptDto> resolved = resolutionEngine.resolveAll(new ArrayList<>(conceptIris));
            if (!resolved.isEmpty()) {
                deviation.setReferencedConceptsResolved(resolved);
            }
        }

        // RPP agenda / ais, both diff sides.
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