package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.client.NkdSparqlClient.PublishedConcept;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.repository.NkdConceptSnapshotRepository;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import com.dia.ismdtoolbackend.utility.published.NkdSnapshotMaterializer;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkdSnapshotServiceImplTest {

    private static final String SKOS_IN_SCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";
    private static final String NKD_IRI = "https://slovník.gov.cz/agendový/104/pojem/adresní-místo";
    private static final String NKD_SCHEME = "https://slovník.gov.cz/agendový/104";
    private static final String OWNER_GRAPH = "https://example.org/slovnik/mestys";
    private static final String OWNER_IRI = OWNER_GRAPH + "/pojem/budova";

    @Mock private NkdConceptSnapshotRepository snapshotRepository;
    @Mock private NkdSparqlClient nkdSparqlClient;
    @Mock private ConceptDeviationComparator conceptDeviationComparator;
    @Spy private NkdSnapshotMaterializer materializer = new NkdSnapshotMaterializer();

    @InjectMocks private NkdSnapshotServiceImpl service;

    private ConceptMetadataEntity owner;

    @BeforeEach
    void setUp() {
        owner = new ConceptMetadataEntity();
        owner.setId(17L);
        owner.setConceptIri(OWNER_IRI);
        owner.setGraphName(OWNER_GRAPH);
    }

    private Model rawNkd(String label) {
        Model m = ModelFactory.createDefaultModel();
        Resource c = m.getResource(NKD_IRI);
        m.add(c, m.createProperty(SKOS_IN_SCHEME), m.getResource(NKD_SCHEME));
        m.add(c, m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"), label);
        return m;
    }

    private ConceptDetailModel detail() {
        return ConceptDetailModel.builder().iri(NKD_IRI).build();
    }

    @Test
    void createOrRefresh_rejectsDisallowedLinkPredicate() {
        OwnerChangeSet cs = new OwnerChangeSet();
        assertThatThrownBy(() -> service.createOrRefreshSnapshot(owner, NKD_IRI, "domain", cs))
                .isInstanceOf(OntologyException.class)
                .hasMessageContaining("domain");

        // Nothing fetched, nothing enqueued.
        assertThat(cs.toAdd).isEmpty();
        assertThat(cs.toRemove).isEmpty();
    }

    @Test
    void createOrRefresh_nkdFound_createsRowAndAddsTriples() {
        when(snapshotRepository.findByOwningConceptIdAndNkdIri(17L, NKD_IRI)).thenReturn(Optional.empty());
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(NKD_IRI))
                .thenReturn(Optional.of(new PublishedConcept(detail(), NKD_SCHEME)));
        when(nkdSparqlClient.fetchPublishedConceptRaw(NKD_IRI)).thenReturn(Optional.of(rawNkd("Adresní místo")));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OwnerChangeSet cs = new OwnerChangeSet();
        NkdConceptSnapshotEntity saved = service.createOrRefreshSnapshot(owner, NKD_IRI, SnapshotLinkType.BROADER_CLASS.value(), cs);

        assertThat(saved).isNotNull();
        assertThat(saved.getOrigin()).isEqualTo(SnapshotOrigin.LINK_TARGET);
        assertThat(saved.getMaterializedTriples()).isNotBlank();
        assertThat(cs.toRemove).isEmpty();                 // new snapshot → no prior set
        assertThat(cs.toAdd).isNotEmpty();                 // raw triples + provenance
        assertThat(cs.toAdd).anyMatch(s ->
                s.getPredicate().getURI().equals(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF));
    }

    @Test
    void createOrRefresh_reSnapshot_removesOldTriplesAddsNew_M1() {
        // Existing row with a STORED old triple set (label "Old").
        NkdConceptSnapshotEntity existing = new NkdConceptSnapshotEntity();
        existing.setOwningConcept(owner);
        existing.setNkdIri(NKD_IRI);
        existing.setGraphName(OWNER_GRAPH);
        existing.setOrigin(SnapshotOrigin.LINK_TARGET);
        existing.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());
        existing.setMaterializedTriples(materializer.toNTriples(
                materializer.materialize(rawNkd("Old"), NKD_IRI, OWNER_IRI, OWNER_GRAPH)));

        when(snapshotRepository.findByOwningConceptIdAndNkdIri(17L, NKD_IRI)).thenReturn(Optional.of(existing));
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(NKD_IRI))
                .thenReturn(Optional.of(new PublishedConcept(detail(), NKD_SCHEME)));
        when(nkdSparqlClient.fetchPublishedConceptRaw(NKD_IRI)).thenReturn(Optional.of(rawNkd("New")));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OwnerChangeSet cs = new OwnerChangeSet();
        service.createOrRefreshSnapshot(owner, NKD_IRI, SnapshotLinkType.BROADER_CLASS.value(), cs);

        // M1: the OLD label triple is in the delete set; the NEW one is in the add set.
        assertThat(cs.toRemove).anyMatch(s -> s.getObject().isLiteral()
                && s.getObject().asLiteral().getString().equals("Old"));
        assertThat(cs.toAdd).anyMatch(s -> s.getObject().isLiteral()
                && s.getObject().asLiteral().getString().equals("New"));
        assertThat(cs.toRemove).noneMatch(s -> s.getObject().isLiteral()
                && s.getObject().asLiteral().getString().equals("New"));
    }

    @Test
    void createOrRefresh_nkdEmpty_existingSnapshot_cascadesRemoval() {
        NkdConceptSnapshotEntity existing = new NkdConceptSnapshotEntity();
        existing.setOwningConcept(owner);
        existing.setNkdIri(NKD_IRI);
        existing.setGraphName(OWNER_GRAPH);
        existing.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());
        existing.setMaterializedTriples(materializer.toNTriples(
                materializer.materialize(rawNkd("Gone"), NKD_IRI, OWNER_IRI, OWNER_GRAPH)));

        when(snapshotRepository.findByOwningConceptIdAndNkdIri(17L, NKD_IRI)).thenReturn(Optional.of(existing));
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(NKD_IRI)).thenReturn(Optional.empty());
        when(snapshotRepository.countByGraphNameAndNkdIri(OWNER_GRAPH, NKD_IRI)).thenReturn(1L);

        OwnerChangeSet cs = new OwnerChangeSet();
        NkdConceptSnapshotEntity result = service.createOrRefreshSnapshot(owner, NKD_IRI, SnapshotLinkType.BROADER_CLASS.value(), cs);

        assertThat(result).isNull();
        verify(snapshotRepository).delete(existing);
        // Cascade removes the materialized copy (last referrer); the dangling owner link triple is
        // the edit's own change-set concern, not create/refresh (no owner-graph view here).
        assertThat(cs.toRemove).anyMatch(s -> s.getSubject().getURI().equals(NKD_IRI));
    }

    /** Owner's outgoing triples: a subClassOf link + the namespaced hierarchy prop, both → nkdIri. */
    private Set<Statement> ownerOutgoingToNkd() {
        Model m = ModelFactory.createDefaultModel();
        Resource owner = m.getResource(OWNER_IRI);
        Resource nkd = m.getResource(NKD_IRI);
        m.add(owner, m.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf"), nkd);
        m.add(owner, m.createProperty(OWNER_GRAPH + "/nadřazená-třída"), nkd);
        m.add(owner, m.createProperty("http://example.org/unrelated"), m.getResource("http://other/x"));
        return m.listStatements().toSet();
    }

    @Test
    void removeSnapshotAndLink_lastReferrer_dropsCopyAndAllLinkPredicates() {
        NkdConceptSnapshotEntity snap = lastReferrerSnapshot();
        when(snapshotRepository.countByGraphNameAndNkdIri(OWNER_GRAPH, NKD_IRI)).thenReturn(1L);

        OwnerChangeSet cs = new OwnerChangeSet();
        service.removeSnapshotAndLink(snap, ownerOutgoingToNkd(), cs);

        // BOTH link predicates to nkdIri removed (subClassOf + hierarchy prop); unrelated edge kept.
        long ownerLinksRemoved = cs.toRemove.stream()
                .filter(s -> s.getSubject().getURI().equals(OWNER_IRI)
                        && s.getObject().isURIResource()
                        && s.getObject().asResource().getURI().equals(NKD_IRI))
                .count();
        assertThat(ownerLinksRemoved).isEqualTo(2);
        assertThat(cs.toRemove).noneMatch(s -> s.getObject().isURIResource()
                && s.getObject().asResource().getURI().equals("http://other/x"));
        // Materialized copy removed (last referrer).
        assertThat(cs.toRemove).anyMatch(s -> s.getSubject().getURI().equals(NKD_IRI));
        verify(snapshotRepository).delete(snap);
    }

    @Test
    void removeSnapshotAndLink_sharedIri_keepsCopy_C2() {
        NkdConceptSnapshotEntity snap = lastReferrerSnapshot();
        when(snapshotRepository.countByGraphNameAndNkdIri(OWNER_GRAPH, NKD_IRI)).thenReturn(2L); // another referrer

        OwnerChangeSet cs = new OwnerChangeSet();
        service.removeSnapshotAndLink(snap, ownerOutgoingToNkd(), cs);

        // Owner's own link triples removed; the shared copy triples (subject = nkdIri) are NOT.
        assertThat(cs.toRemove).anyMatch(s -> s.getSubject().getURI().equals(OWNER_IRI)
                && s.getObject().asResource().getURI().equals(NKD_IRI));
        assertThat(cs.toRemove).noneMatch(s -> s.getSubject().getURI().equals(NKD_IRI));
        verify(snapshotRepository).delete(snap);
    }

    private NkdConceptSnapshotEntity lastReferrerSnapshot() {
        NkdConceptSnapshotEntity snap = new NkdConceptSnapshotEntity();
        snap.setOwningConcept(owner);
        snap.setNkdIri(NKD_IRI);
        snap.setGraphName(OWNER_GRAPH);
        snap.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());
        snap.setMaterializedTriples(materializer.toNTriples(
                materializer.materialize(rawNkd("X"), NKD_IRI, OWNER_IRI, OWNER_GRAPH)));
        return snap;
    }

    @Test
    void evaluateDeviation_cachesStatusAndTimestamp() {
        NkdConceptSnapshotEntity snap = new NkdConceptSnapshotEntity();
        snap.setNkdIri(NKD_IRI);
        snap.setSnapshot(detail());

        when(nkdSparqlClient.fetchPublishedConcept(NKD_IRI)).thenReturn(Optional.of(detail()));
        when(conceptDeviationComparator.compareConceptDetails(any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.HAS_DEVIATIONS).build());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PublishedConceptDeviationModel result = service.evaluateDeviation(snap);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        assertThat(snap.getLastDeviationStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        assertThat(snap.getLastCheckedAt()).isNotNull();
    }

    @Test
    void evaluateDeviation_conceptGoneFromNkd_returnsNotFound() {
        NkdConceptSnapshotEntity snap = new NkdConceptSnapshotEntity();
        snap.setNkdIri(NKD_IRI);
        snap.setSnapshot(detail());

        when(nkdSparqlClient.fetchPublishedConcept(NKD_IRI)).thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PublishedConceptDeviationModel result = service.evaluateDeviation(snap);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
        verify(conceptDeviationComparator, never()).compareConceptDetails(any(), any());
    }

    @Test
    void findForConcept_delegatesToRepository() {
        NkdConceptSnapshotEntity snap = new NkdConceptSnapshotEntity();
        when(snapshotRepository.findByOwningConceptId(17L)).thenReturn(List.of(snap));
        assertThat(service.findForConcept(17L)).containsExactly(snap);
    }
}
