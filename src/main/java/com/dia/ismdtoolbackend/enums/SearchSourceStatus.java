package com.dia.ismdtoolbackend.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SearchSourceStatus {
    OK("OK"),
    DEGRADED("DEGRADED"),
    TIMEOUT("TIMEOUT"),
    ERROR("ERROR"),
    SKIPPED("SKIPPED"),
    UNAVAILABLE("UNAVAILABLE");

    private final String value;

    SearchSourceStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
