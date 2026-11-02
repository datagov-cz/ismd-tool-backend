package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.github.benmanes.caffeine.cache.Weigher;

import java.util.List;

/**
 * Estimated heap bytes of an e-Sbírka content entry: 2 bytes per character (Czech text is stored
 * as UTF-16) plus a fixed per-object overhead. A whole version runs to ~10 MB for the largest laws,
 * so these caches are bounded by weight, not entry count.
 */
final class EsbirkaContentWeigher implements Weigher<Object, Object> {

    private static final int NODE_OVERHEAD = 160;
    private static final int UNKNOWN_ENTRY = 1024;

    @Override
    public int weigh(Object key, Object value) {
        long bytes;
        if (value instanceof LawContentDto content) {
            bytes = chars(content.getBodyHtml()) * 2L + nodes(content.getFragments())
                    + (content.getVersions() == null ? 0 : content.getVersions().size() * (long) NODE_OVERHEAD * 2);
        } else if (value instanceof List<?> list) {
            bytes = list.stream().mapToLong(EsbirkaContentWeigher::model).sum();
        } else {
            bytes = UNKNOWN_ENTRY;
        }
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    private static long model(Object item) {
        if (!(item instanceof FragmentModel f)) {
            return NODE_OVERHEAD;
        }
        return NODE_OVERHEAD + 2L * (chars(f.getIri()) + chars(f.getParentIri()) + chars(f.getCitation())
                + chars(f.getKind()) + chars(f.getOrder()) + chars(f.getBodyHtml()));
    }

    private static long nodes(List<FragmentDto> nodes) {
        if (nodes == null) {
            return 0;
        }
        long bytes = 0;
        for (FragmentDto n : nodes) {
            bytes += NODE_OVERHEAD + 2L * (chars(n.getIri()) + chars(n.getEliPath()) + chars(n.getCitation())
                    + chars(n.getKind()) + chars(n.getOrder()) + chars(n.getBodyHtml()))
                    + nodes(n.getChildren());
        }
        return bytes;
    }

    private static int chars(String s) {
        return s == null ? 0 : s.length();
    }
}
