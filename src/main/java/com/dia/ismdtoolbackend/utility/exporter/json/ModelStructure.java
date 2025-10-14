package com.dia.ismdtoolbackend.utility.exporter.json;

import lombok.Builder;
import lombok.Data;
import org.apache.jena.rdf.model.Resource;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ModelStructure {
    private final String modelName;
    private final String modelDescription;
    private final Map<String, String> modelProperties;
    private final String effectiveNamespace;
    private final String ontologyIRI;
    private final Resource vocabularyResource;
    private final Map<String, Resource> resourceMap;
    private final String creationDate;
    private final String modificationDate;
    private final List<String> vocabularyTypes;
}
