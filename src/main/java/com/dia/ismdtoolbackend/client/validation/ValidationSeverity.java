package com.dia.ismdtoolbackend.client.validation;

import lombok.Getter;

@Getter
public enum ValidationSeverity {
    ERROR("Chyba"),
    WARNING("Varování"),
    INFO("Informace");

    private final String displayName;

    ValidationSeverity(String displayName) {
        this.displayName = displayName;
    }
}