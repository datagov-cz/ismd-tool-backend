package com.dia.ismdtoolbackend.testsupport.fixtures;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Multilingual text models (name, description, definition, alternative names) for tests. */
public final class Texts {

    private Texts() {
    }

    public static NameModel name(String lang, String value) {
        return name(singleton(lang, value));
    }

    public static NameModel name(Map<String, String> byLang) {
        NameModel model = new NameModel();
        model.setName(byLang);
        return model;
    }

    public static DescriptionModel description(String lang, String value) {
        return description(singleton(lang, value));
    }

    public static DescriptionModel description(Map<String, String> byLang) {
        DescriptionModel model = new DescriptionModel();
        model.setDescription(byLang);
        return model;
    }

    public static DefinitionModel definition(String lang, String value) {
        return definition(singleton(lang, value));
    }

    public static DefinitionModel definition(Map<String, String> byLang) {
        DefinitionModel model = new DefinitionModel();
        model.setDefinition(byLang);
        return model;
    }

    public static AltNameModel altName(String lang, String... values) {
        Map<String, List<String>> byLang = new LinkedHashMap<>();
        byLang.put(lang, List.of(values));
        return altNames(byLang);
    }

    public static AltNameModel altNames(Map<String, List<String>> byLang) {
        AltNameModel model = new AltNameModel();
        model.setAltName(byLang);
        return model;
    }

    /** A mutable one-entry map, so null keys and values (which {@code Map.of} rejects) can be expressed. */
    private static Map<String, String> singleton(String lang, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(lang, value);
        return map;
    }
}
