package com.dia.ismdtoolbackend.testsupport.fixtures;

import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;

import java.util.function.Consumer;

/**
 * Concept create models for tests: a concept type and a Czech name, every other field
 * {@code null}.
 *
 * <pre>{@code
 * ConceptModels.trida("Osoba", m -> m.setType("subjekt"));
 * }</pre>
 */
public final class ConceptModels {

    private ConceptModels() {
    }

    public static ClassConceptModel trida(String csName) {
        ClassConceptModel model = new ClassConceptModel();
        model.setConceptType("TRIDA");
        model.setNameModel(Texts.name("cs", csName));
        return model;
    }

    public static ClassConceptModel trida(String csName, Consumer<ClassConceptModel> fields) {
        ClassConceptModel model = trida(csName);
        fields.accept(model);
        return model;
    }

    public static PropertyConceptModel vlastnost(String csName) {
        PropertyConceptModel model = new PropertyConceptModel();
        model.setConceptType("VLASTNOST");
        model.setNameModel(Texts.name("cs", csName));
        return model;
    }

    public static PropertyConceptModel vlastnost(String csName, Consumer<PropertyConceptModel> fields) {
        PropertyConceptModel model = vlastnost(csName);
        fields.accept(model);
        return model;
    }

    public static RelationshipConceptModel vztah(String csName) {
        RelationshipConceptModel model = new RelationshipConceptModel();
        model.setConceptType("VZTAH");
        model.setNameModel(Texts.name("cs", csName));
        return model;
    }

    public static RelationshipConceptModel vztah(String csName, Consumer<RelationshipConceptModel> fields) {
        RelationshipConceptModel model = vztah(csName);
        fields.accept(model);
        return model;
    }
}
