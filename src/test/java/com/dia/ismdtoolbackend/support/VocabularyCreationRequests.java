package com.dia.ismdtoolbackend.support;

import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto.*;
import com.dia.ismdtoolbackend.controller.dto.ai.AiConceptReferenceDto;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.dia.ismdtoolbackend.models.*;
import java.util.List;
import java.util.Map;

public final class VocabularyCreationRequests {
    private VocabularyCreationRequests() {}
    public static final String GRAPH = "https://example.org/doprava";
    public static final String LEGAL = "https://e-sbirka.gov.cz/eli/cz/sb/2000/361/2024-01-01/dokument/norma/cast_1/hlava_1/par_2/pism_g";

    public static OntologyCreateWithConceptsRequestDto sample() {
        var ontology = new OntologyCreateModel();
        ontology.setNamespace("https://example.org/");
        var name = new NameModel();
        name.setName(Map.of("cs", "Doprava"));
        ontology.setNameModel(name);
        var description = new DescriptionModel();
        description.setDescription(Map.of("cs", "Rozpracovaný slovník dopravy"));
        ontology.setDescriptionModel(description);
        return new OntologyCreateWithConceptsRequestDto(ontology,
                List.of(clazz("driver", "Řidič"), clazz("vehicle", "Vozidlo")),
                List.of(new AttributeDto("weight", ref("vehicle"), Map.of("cs", "Hmotnost"),
                        Map.of("cs", "Hmotnost vozidla"), Map.of("cs", "Hmotnost v kilogramech"), LEGAL, "xsd:decimal")),
                List.of(new RelationshipDto("drives", ref("driver"), ref("vehicle"), Map.of("cs", "řídí"),
                        null, null, LEGAL)));
    }

    public static ClassDto clazz(String ref, String name) {
        return new ClassDto(ref, Map.of("cs", name), Map.of("cs", "Definice " + name),
                Map.of("cs", "Vysvětlení " + name), AiTermType.CLASS, List.of(), LEGAL);
    }

    public static AiConceptReferenceDto ref(String ref) { return new AiConceptReferenceDto(ref, null); }
}
