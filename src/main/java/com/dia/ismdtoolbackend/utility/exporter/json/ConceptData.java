package com.dia.ismdtoolbackend.utility.exporter.json;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ConceptData {
    private final List<Map<String, Object>> concepts;
    private final int totalConceptCount;

    public static ConceptData empty() {
        return ConceptData.builder()
                .concepts(List.of())
                .totalConceptCount(0)
                .build();
    }
}
