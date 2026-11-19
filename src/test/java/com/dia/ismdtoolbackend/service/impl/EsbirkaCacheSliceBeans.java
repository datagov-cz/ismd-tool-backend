package com.dia.ismdtoolbackend.service.impl;

import org.springframework.context.annotation.Import;

/**
 * The e-Sbírka service beans behind its caches; one context for every e-Sbírka cache test.
 * Deliberately not a {@code @Configuration}, so component scanning in full-context tests skips it.
 */
@Import({
        EsbirkaServiceImpl.class,
        EsbirkaLawSearch.class,
        EsbirkaLawVersionsReader.class,
        EsbirkaLawContentReader.class,
        EsbirkaLegalSourceResolver.class,
        EsbirkaFragmentResolutionCache.class,
        EsbirkaSubtreeBodyCache.class
})
class EsbirkaCacheSliceBeans {
}
