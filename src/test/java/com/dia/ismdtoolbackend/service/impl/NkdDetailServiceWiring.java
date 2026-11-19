package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;

/** Builds an {@link NkdDetailServiceImpl} and its focused classes from leaf collaborators, for tests. */
public final class NkdDetailServiceWiring {

    private NkdDetailServiceWiring() {
    }

    public static NkdDetailServiceImpl build(NkdSparqlClient nkdSparqlClient,
                                             JsonExporter jsonExporter,
                                             RppSnapshotHolder rppSnapshotHolder,
                                             NkodCodelistService nkodCodelistService,
                                             ReferencedConceptsEnricher referencedConceptsEnricher) {
        NkdRequestGuard guard = new NkdRequestGuard(nkdSparqlClient);
        return new NkdDetailServiceImpl(nkdSparqlClient, jsonExporter, rppSnapshotHolder, nkodCodelistService,
                referencedConceptsEnricher, guard,
                new NkdOntologyListReader(nkdSparqlClient, guard, new NkdCatalogTotalsReader(nkdSparqlClient)),
                new NkdConceptListReader(nkdSparqlClient, guard));
    }
}
