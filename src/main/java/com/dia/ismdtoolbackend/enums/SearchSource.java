package com.dia.ismdtoolbackend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(type = "string", allowableValues = {"NKD", "ISMD", "UNPUBLISHED", "ALL"})
public enum SearchSource {
    NKD("NKD"),
    ISMD("ISMD"),
    /**
     * "Rozpracovaný" — everything held locally in ISMD, with no publish-state
     * restriction. NKD is the published world and ISMD is the workbench, so a local
     * row is by definition material the user is working on. {@code is_published = true}
     * marks an IRI that resolves in NKD, not a resource that is finished: an uploaded
     * working copy is fully published-flagged until a concept is edited, so filtering
     * on the flag hid whole vocabularies. Drafts sort first. Anonymous requests are
     * rejected at the service layer.
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