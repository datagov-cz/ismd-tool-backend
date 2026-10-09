package com.dia.ismdtoolbackend.models.diagram;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one mapper for every diagram JSON column — node overlays, edge waypoints, staged edits — and for the
 * readers that parse those columns outside their entity.
 *
 * <p>Shared because the configuration is a compatibility contract, not a preference: a value written by one
 * mapper is read back by another, so a setting present on one side and absent on the other is a silent
 * round-trip bug.
 *
 * <p>A static rather than an injected bean: these are read from JPA entities, which Spring does not
 * autowire, and the repo has no shared {@code ObjectMapper} bean to inject anyway. {@code ObjectMapper} is
 * thread-safe once configured, and nothing here reconfigures it.
 */
public final class DiagramJson {

    /**
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} is off so a column written by a newer version still parses after a
     * rollback; dates are written as ISO-8601 text rather than numeric timestamps so a stored value stays
     * readable and comparable. Jackson 2 defaults keep columns written before the Jackson 3 migration
     * readable.
     */
    public static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private DiagramJson() {
    }
}