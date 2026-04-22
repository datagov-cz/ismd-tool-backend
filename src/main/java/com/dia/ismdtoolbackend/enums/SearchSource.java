package com.dia.ismdtoolbackend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(type = "string", allowableValues = {"NKD", "ISMD", "ALL"})
public enum SearchSource {
    NKD("NKD"),
    ISMD("ISMD"),
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
                "Unknown search source: " + value + ". Valid values: NKD, ISMD, ALL"
        );
    }

    @Override
    public String toString() {
        return value;
    }
}