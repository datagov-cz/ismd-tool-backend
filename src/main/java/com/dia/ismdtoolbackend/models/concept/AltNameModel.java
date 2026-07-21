package com.dia.ismdtoolbackend.models.concept;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Alternative names (`skos:altLabel`) keyed by language tag. A language may hold several alt labels,
 * so each key maps to a list.
 *
 * <p>The deserializer is tolerant: a language key may arrive as a bare string ({@code {"cs": "Obec"}})
 * or as a list ({@code {"cs": ["Obec", "Municipalita"]}}). Both are read; the string form is wrapped
 * into a single-element list. Serialization always emits the list form.
 */
@Data
@Getter
@Setter
public class AltNameModel {

    @JsonDeserialize(using = AltNameValuesDeserializer.class)
    private Map<String, List<String>> altName;

}