package com.dia.ismdtoolbackend.analyzer;

import java.util.Set;

public record AnalysisResult(Set<String> requiredBaseClasses, Set<String> requiredProperties) {
}
