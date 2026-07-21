package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.PropertyDeviation;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolution of a deviation's concept and RPP references onto navigable metadata — both sides of every
 * diff, keyed by IRI. Origin-agnostic (WORKING_COPY and LINK_TARGET share it).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviationResolutionEnricherTest {

    @Mock private ReferencedConceptResolutionEngine resolutionEngine;
    @Mock private RppSnapshotHolder rppSnapshotHolder;

    @InjectMocks private DeviationResolutionEnricher enricher;

    private static PropertyDeviation<String> diff(String local, String published) {
        return PropertyDeviation.<String>builder()
                .localValue(local).publishedValue(published).isDifferent(true).build();
    }

    private static ResolvedConceptDto resolved(String iri) {
        return ResolvedConceptDto.builder().iri(iri).build();
    }

    @Test
    void resolvesSourceDomainRangeAgendaAis_bothSides() {
        String source = "https://slovník.gov.cz/x/pojem/obec";
        String domainLocal = "https://slovník.gov.cz/a/pojem/mistni-domena";
        String domainPublished = "https://slovník.gov.cz/a/pojem/nkd-domena";
        String rangeConcept = "https://slovník.gov.cz/a/pojem/cilova-trida";
        String agendaIri = "https://rpp/agenda/A123";
        String aisIri = "https://rpp/isvs/S456";

        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .source(NkdConceptRefDto.builder().iri(source).build())
                .domain(diff(domainLocal, domainPublished))
                .range(diff("xsd:string", rangeConcept))   // one datatype side, one concept side
                .agenda(diff(agendaIri, agendaIri))
                .ais(diff(aisIri, null))
                .build();

        when(resolutionEngine.resolveAll(anyList())).thenReturn(Map.of(
                source, resolved(source),
                domainLocal, resolved(domainLocal),
                domainPublished, resolved(domainPublished),
                rangeConcept, resolved(rangeConcept)));
        RppAgenda agenda = new RppAgenda();
        agenda.setIri(agendaIri);
        when(rppSnapshotHolder.findAgendaByIri(agendaIri)).thenReturn(Optional.of(agenda));
        RppIsvs isvs = new RppIsvs();
        isvs.setIri(aisIri);
        when(rppSnapshotHolder.findIsvsByIri(aisIri)).thenReturn(Optional.of(isvs));

        enricher.enrich(deviation);

        assertThat(deviation.getReferencedConceptsResolved())
                .containsKeys(source, domainLocal, domainPublished, rangeConcept);
        // The datatype side of range resolves to a DataTypeDto, not a concept.
        assertThat(deviation.getRangeResolved()).containsKey("xsd:string");
        assertThat(deviation.getReferencedConceptsResolved()).doesNotContainKey("xsd:string");
        assertThat(deviation.getAgendaResolved()).containsKey(agendaIri);
        assertThat(deviation.getAisResolved()).containsKey(aisIri);
    }

    @Test
    void nothingToResolve_leavesResolvedFieldsNull_andDoesNotCallResolvers() {
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder().build();

        enricher.enrich(deviation);

        assertThat(deviation.getReferencedConceptsResolved()).isNull();
        assertThat(deviation.getRangeResolved()).isNull();
        assertThat(deviation.getAgendaResolved()).isNull();
        assertThat(deviation.getAisResolved()).isNull();
        verify(resolutionEngine, never()).resolveAll(anyList());
    }

    @Test
    void nullDeviation_isNoOp() {
        enricher.enrich(null);
        verify(resolutionEngine, never()).resolveAll(anyList());
    }
}