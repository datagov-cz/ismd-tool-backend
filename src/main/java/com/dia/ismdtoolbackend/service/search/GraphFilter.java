package com.dia.ismdtoolbackend.service.search;

import java.util.List;

/** Pre-computed graph-name views derived from the request's ontology filter. */
record GraphFilter(boolean hasGraphFilter,
                   List<String> graphNames,
                   List<String> searchGraphs) {
}
