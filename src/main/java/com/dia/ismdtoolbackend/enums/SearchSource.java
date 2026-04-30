package com.dia.ismdtoolbackend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(type = "string", allowableValues = {"NKD", "ISMD", "UNPUBLISHED", "ALL"})
public enum SearchSource {
    NKD("NKD"),
    ISMD("ISMD"),
    /**
     * Local ISMD results restricted to {@code is_published = false}. Behaves like ISMD
     * in every other respect but drops any concept/ontology that is already published.
     * Visibility: an admin sees all unpublished resources; a regular user sees only
     * their own. Anonymous requests are rejected at the service layer.
     */
    UNPUBLISHED("UNPUBLISHED"),
    ALL("ALL");

    private final String value;

    SearchSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SearchSource fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        for (SearchSource source : SearchSource.values()) {
            if (source.value.equalsIgnoreCase(trimmed)) {
                return source;
            }
        }
        throw new IllegalArgumentException(
                "Unknown search source: " + value + ". Valid values: NKD, ISMD, UNPUBLISHED, ALL"
        );
    }

    @Override
    public String toString() {
        return value;
    }
}