package com.dia.ismdtoolbackend.reconciler;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detection-only PG↔TDB2 consistency reconciler. Compares the OWNED concept subjects in
 * Fuseki/TDB2 against the Postgres metadata rows and reports drift. It NEVER mutates either
 * store in this phase — auto-repair (with quarantine + audit) is a later step.
 *
 * <p>Read order is TDB2-first, PG-last (plan §1): writes are TDB2-first, so a concept whose
 * RDF exists but whose PG commit is still in-flight is most likely to be visible in the PG
 * snapshot if we take it last, shrinking the false-orphan window.
 *
 * <p>The owned-subject enumeration uses {@link JenaTDB2Repository#OWNED_CONCEPT_PATTERN}, the
 * same predicate the live resolver uses, so referenced/external concepts (object-only) and
 * "excluded" no-{@code inScheme} concepts are never mistaken for orphans.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConsistencyReconciler {

    private static final String POJEM_SEGMENT = "/pojem/";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    private final JenaTDB2Repository jenaTDB2Repository;
    private final PgMetadataSnapshot pgMetadataSnapshot;

    /** Re-entrancy guard so an overlapping scheduled tick and a manual trigger can't double-run. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Runs one detection pass. Returns null if a run is already in progress (caller should
     * surface a 409 / skip).
     *
     * <p>NOT {@code @Transactional} itself — Pass A does slow Fuseki HTTP I/O and must not hold
     * a DB connection across it. The PG snapshot (Pass B) is taken via
     * {@link PgMetadataSnapshot#load()}, which owns the single read-only transaction so the two
     * {@code findAll()}s see one consistent state.
     *
     * <p>Any {@code JenaTDB2Exception} from the TDB2 enumeration propagates — a half-read
     * TDB2 must abort the whole run rather than produce bogus PG-side conclusions.
     */
    public ReconciliationReport reconcile(String triggeredBy) {
        if (!running.compareAndSet(false, true)) {
            log.info("Reconcile requested by {} but a run is already in progress; skipping", triggeredBy);
            return null;
        }
        Instant startedAt = Instant.now();
        try {
            // PASS A — enumerate TDB2 first.
            List<String> graphs = jenaTDB2Repository.listNamedGraphs();
            Map<String, Set<String>> ownedIriToGraphs = new HashMap<>();
            Set<String> graphsWithOwnedSubjects = new HashSet<>();
            int ownedRdfCount = 0;
            for (String g : graphs) {
                List<String> owned = jenaTDB2Repository.listOwnedConceptIrisInGraph(g);
                if (!owned.isEmpty()) {
                    graphsWithOwnedSubjects.add(g);
                }
                for (String iri : owned) {
                    ownedIriToGraphs.computeIfAbsent(iri, k -> new HashSet<>()).add(g);
                    ownedRdfCount++;
                }
            }

            // PASS B — snapshot PG last (one consistent read-only transaction).
            PgMetadataSnapshot.Snapshot pg = pgMetadataSnapshot.load();
            List<ConceptMetadataEntity> pgConcepts = pg.concepts();
            Map<String, ConceptMetadataEntity> pgByIri = pg.byIri();
            Set<String> knownGraphs = pg.knownGraphs();

            // --- RDF -> PG: raw orphans (before rename pairing) ---
            List<RawOrphan> rdfOrphans = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : ownedIriToGraphs.entrySet()) {
                String iri = e.getKey();
                if (!pgByIri.containsKey(iri)) {
                    // The IRI is owned in (usually exactly one) graph. Pick deterministically —
                    // a HashSet iteration order would make the attributed graph (and therefore
                    // rename-pair matching, which keys on graph) vary run-to-run for the rare
                    // multi-graph case. Sort so the same input always yields the same finding.
                    String g = e.getValue().stream().sorted().findFirst().orElseThrow();
                    rdfOrphans.add(new RawOrphan(iri, g));
                }
            }

            // --- PG -> RDF: raw missing / mismatch (before rename pairing) ---
            List<Mismatch> mismatches = new ArrayList<>();
            List<RawMissing> pgMissing = new ArrayList<>();
            for (ConceptMetadataEntity c : pgConcepts) {
                Set<String> foundGraphs = ownedIriToGraphs.get(c.getConceptIri());
                if (foundGraphs == null) {
                    pgMissing.add(new RawMissing(c));
                } else if (c.getGraphName() == null) {
                    // The IRI is owned-resolvable in TDB2 but the PG row has no graphName at all —
                    // incomplete metadata. Without the null branch this short-circuits to "consistent",
                    // hiding a real data-quality defect. Report-only (PG-authority can't pick a graph for it).
                    mismatches.add(Mismatch.of(MismatchCategory.IRI_GRAPH_MISMATCH, null,
                            c.getConceptIri(),
                            "PG row has null graphName but the IRI is owned-resolvable in " + foundGraphs));
                } else if (!foundGraphs.contains(c.getGraphName())) {
                    mismatches.add(Mismatch.of(MismatchCategory.IRI_GRAPH_MISMATCH, c.getGraphName(),
                            c.getConceptIri(),
                            "Owned-resolvable in " + foundGraphs + " but PG graphName is " + c.getGraphName()));
                }
            }

            // --- GRAPH_ORPHAN: TDB2 graph with owned subjects, no PG ontology row ---
            for (String g : graphsWithOwnedSubjects) {
                if (!knownGraphs.contains(g)) {
                    mismatches.add(Mismatch.graphOrphan(g, "Fuseki graph has owned concepts but no ontology row"));
                }
            }

            // --- R1: pair rename candidates, consuming matched raw orphans/missing ---
            detectRenamePairs(rdfOrphans, pgMissing, mismatches);

            // --- finalize the unpaired raw orphans/missing ---
            for (RawOrphan o : rdfOrphans) {
                mismatches.add(Mismatch.of(MismatchCategory.RDF_ORPHAN, o.graph(), o.iri(),
                        "Owned RDF subject with no Postgres row"));
            }
            for (RawMissing m : pgMissing) {
                mismatches.add(Mismatch.of(MismatchCategory.PG_MISSING_RDF, m.concept().getGraphName(),
                        m.concept().getConceptIri(),
                        "PG row with no owned-resolvable RDF in ANY graph (failed delete, failed create, or lost inScheme)"));
            }

            ReconciliationReport report = new ReconciliationReport(
                    startedAt, Instant.now(), triggeredBy,
                    graphs.size(), ownedRdfCount, pgConcepts.size(), List.copyOf(mismatches));
            log.info("Reconcile ({}) complete: {} graphs, {} owned RDF, {} PG concepts, {} mismatches {}",
                    triggeredBy, graphs.size(), ownedRdfCount, pgConcepts.size(),
                    report.totalMismatches(), report.countsByCategory());
            return report;
        } finally {
            running.set(false);
        }
    }

    /**
     * R1 rename-pair detector. For each raw RDF orphan (new IRI) tries to find a raw PG-missing
     * row (old IRI) in the SAME graph whose name/label matches and whose IRI differs only in the
     * trailing {@code /pojem/<name>} segment. On a match, removes both from their raw lists and
     * emits one report-only {@link MismatchCategory#SUSPECTED_RENAME}. Fails toward NOT pairing
     * (and thus toward reporting plain orphans) when the label can't be confirmed.
     */
    private void detectRenamePairs(List<RawOrphan> rdfOrphans, List<RawMissing> pgMissing,
                                   List<Mismatch> out) {
        if (rdfOrphans.isEmpty() || pgMissing.isEmpty()) {
            return;
        }
        // Batch-fetch labels for the orphan IRIs so we can label-match without per-IRI queries.
        List<String> orphanIris = rdfOrphans.stream().map(RawOrphan::iri).toList();
        Map<String, Set<String>> orphanLabels = fetchLowerLabels(orphanIris);

        List<RawOrphan> orphansToKeep = new ArrayList<>();
        for (RawOrphan orphan : rdfOrphans) {
            RawMissing match = findRenameMatch(orphan, pgMissing, orphanLabels.getOrDefault(orphan.iri(), Set.of()));
            if (match == null) {
                orphansToKeep.add(orphan);
                continue;
            }
            pgMissing.remove(match);
            out.add(Mismatch.suspectedRename(orphan.graph(), orphan.iri(), match.concept().getConceptIri(),
                    "Same graph + matching label + IRI differs only in /pojem/ tail — likely a half-finished rename"));
        }
        rdfOrphans.clear();
        rdfOrphans.addAll(orphansToKeep);
    }

    private RawMissing findRenameMatch(RawOrphan orphan, List<RawMissing> candidates, Set<String> orphanLabelsLower) {
        String orphanPrefix = pojemPrefix(orphan.iri());
        if (orphanPrefix == null) {
            return null;
        }
        for (RawMissing cand : candidates) {
            ConceptMetadataEntity c = cand.concept();
            if (!orphan.graph().equals(c.getGraphName())) {
                continue;
            }
            String candPrefix = pojemPrefix(c.getConceptIri());
            if (candPrefix == null || !candPrefix.equals(orphanPrefix)) {
                continue;
            }
            if (labelMatches(orphanLabelsLower, c)) {
                return cand;
            }
        }
        return null;
    }

    /** True if the PG row's name appears among the orphan's TDB2 labels (case-insensitive). */
    private boolean labelMatches(Set<String> orphanLabelsLower, ConceptMetadataEntity c) {
        if (orphanLabelsLower.isEmpty()) {
            return false; // can't confirm — fail toward not pairing
        }
        String name = c.getConceptName();
        return name != null && orphanLabelsLower.contains(name.toLowerCase(Locale.ROOT));
    }

    /** The IRI prefix up to and including {@code /pojem/}, or null if the IRI has no such segment. */
    private String pojemPrefix(String iri) {
        if (iri == null) {
            return null;
        }
        int idx = iri.lastIndexOf(POJEM_SEGMENT);
        return idx < 0 ? null : iri.substring(0, idx + POJEM_SEGMENT.length());
    }

    /** Batch-fetches lowercased {@code skos:prefLabel} strings per concept IRI from Fuseki. */
    private Map<String, Set<String>> fetchLowerLabels(List<String> conceptIris) {
        Map<String, Set<String>> labels = new HashMap<>();
        if (conceptIris.isEmpty()) {
            return labels;
        }
        Model model = jenaTDB2Repository.fetchConceptLabels(conceptIris);
        Property prefLabel = model.createProperty(SKOS_PREF_LABEL);
        StmtIterator it = model.listStatements(null, prefLabel, (RDFNode) null);
        try {
            while (it.hasNext()) {
                var stmt = it.next();
                if (!stmt.getSubject().isURIResource() || !stmt.getObject().isLiteral()) {
                    continue;
                }
                String iri = stmt.getSubject().getURI();
                String text = stmt.getObject().asLiteral().getString();
                if (text != null && !text.isBlank()) {
                    labels.computeIfAbsent(iri, k -> new HashSet<>()).add(text.toLowerCase(Locale.ROOT));
                }
            }
        } finally {
            it.close();
        }
        return labels;
    }

    /** A raw RDF→PG orphan before rename pairing. */
    private record RawOrphan(String iri, String graph) {}

    /** A raw PG→RDF missing row before rename pairing. */
    private record RawMissing(ConceptMetadataEntity concept) {}
}