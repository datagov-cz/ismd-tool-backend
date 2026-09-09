package com.dia.ismdtoolbackend.models.diagram;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one mapper for every diagram JSON column — node overlays, edge waypoints, staged edits — and for the
 * readers that parse those columns outside their entity.
 *
 * <p>Shared because the configuration is a compatibility contract, not a preference: a value written by one
 * mapper is read back by another, so a setting present on one side and absent on the other is a silent
 * round-trip bug. Four separate copies had already drifted — the edge entity's omitted
 * {@link JavaTimeModule}, harmless only because {@link EdgeWaypoint} happens to hold no temporal field.
 *
 * <p>A static rather than an injected bean: these are read from JPA entities, which Spring does not
 * autowire, and the repo has no shared {@code ObjectMapper} bean to inject anyway. {@code ObjectMapper} is
 * thread-safe once configured, and nothing here reconfigures it.
 */
public final class DiagramJson {

    /**
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} is off so a column written by a newer version still parses after a
     * rollback; dates are written as ISO-8601 text rather than numeric timestamps so a stored value stays
     * readable and comparable.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DiagramJson() {
    }
}