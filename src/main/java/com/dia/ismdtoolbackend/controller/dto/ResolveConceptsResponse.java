package com.dia.ismdtoolbackend.controller.dto;

import java.util.Map;

/**
 * Response body for {@code POST /api/ontology/concepts/resolve}: a map keyed by
 * input IRI. IRIs that couldn't be resolved against either ISMD or NKD are
 * absent (no error, no null entry).
 */
public record ResolveConceptsResponse(
        Map<String, ResolvedConceptDto> resolved
) {
}
