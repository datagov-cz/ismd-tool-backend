package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;

/** Builds an {@link EsbirkaServiceImpl} and its focused classes from leaf collaborators, for tests. */
final class EsbirkaServiceWiring {

    private EsbirkaServiceWiring() {
    }

    static EsbirkaServiceImpl build(EsbirkaSparqlClient client,
                                    EsbirkaFragmentResolutionCache resolutionCache,
                                    EsbirkaSubtreeBodyCache subtreeBodyCache) {
        EsbirkaLawVersionsReader versionsReader = new EsbirkaLawVersionsReader(client);
        return new EsbirkaServiceImpl(
                new EsbirkaLawSearch(client),
                versionsReader,
                new EsbirkaLawContentReader(client, versionsReader),
                new EsbirkaLegalSourceResolver(resolutionCache, subtreeBodyCache));
    }
}
