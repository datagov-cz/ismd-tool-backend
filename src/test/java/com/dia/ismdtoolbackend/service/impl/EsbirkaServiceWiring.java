package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.service.EsbirkaService;

/** Builds an {@link EsbirkaServiceImpl} and its focused classes from leaf collaborators, for tests. */
public final class EsbirkaServiceWiring {

    private EsbirkaServiceWiring() {
    }

    static EsbirkaServiceImpl build(EsbirkaClient client,
                                    EsbirkaFragmentResolutionCache resolutionCache,
                                    EsbirkaSubtreeBodyCache subtreeBodyCache) {
        EsbirkaLawVersionsReader versionsReader = new EsbirkaLawVersionsReader(client);
        return new EsbirkaServiceImpl(
                new EsbirkaLawSearch(client),
                versionsReader,
                new EsbirkaLawContentReader(client, versionsReader),
                new EsbirkaLegalSourceResolver(resolutionCache, subtreeBodyCache));
    }

    /** For tests outside this package, which cannot name the package-private caches. No resolve support. */
    public static EsbirkaService forClient(EsbirkaClient client) {
        return build(client, null, null);
    }
}