package com.dia.ismdtoolbackend.utility.published;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;

import java.util.HashSet;
import java.util.Set;

/**
 * Removes materialized NKD snapshot copies from a model in place. Snapshot copies live inside the owner's
 * named graph (see {@link NkdSnapshotMaterializer}) and carry real concept types, so the ontology-detail
 * enumeration would otherwise list them in {@code pojmy}. Linked NKD concepts surface only via
 * {@code linkSnapshots}, so they must be stripped before {@code pojmy} is built.
 */
@Slf4j
public final class NkdSnapshotTripleFilter {

    private NkdSnapshotTripleFilter() {
    }

    /**
     * Removes every triple whose subject is a materialized NKD copy — i.e. any subject bearing an
     * {@link NkdSnapshotMaterializer#NKD_SNAPSHOT_OF} marker. Mutates {@code model} and returns the number
     * of snapshot subjects removed.
     */
    public static int removeSnapshotSubjects(Model model) {
        if (model == null || model.isEmpty()) {
            return 0;
        }

        Property snapshotOf = model.createProperty(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF);

        Set<Resource> snapshotSubjects = new HashSet<>();
        StmtIterator markers = model.listStatements(null, snapshotOf, (org.apache.jena.rdf.model.RDFNode) null);
        try {
            while (markers.hasNext()) {
                snapshotSubjects.add(markers.next().getSubject());
            }
        } finally {
            markers.close();
        }

        if (snapshotSubjects.isEmpty()) {
            return 0;
        }

        Set<Statement> doomed = new HashSet<>();
        for (Resource subject : snapshotSubjects) {
            StmtIterator it = model.listStatements(subject, null, (org.apache.jena.rdf.model.RDFNode) null);
            try {
                while (it.hasNext()) {
                    doomed.add(it.next());
                }
            } finally {
                it.close();
            }
        }
        model.remove(doomed.toArray(new Statement[0]));

        log.debug("Stripped {} NKD snapshot subjects ({} triples) from model before detail extraction",
                snapshotSubjects.size(), doomed.size());
        return snapshotSubjects.size();
    }
}