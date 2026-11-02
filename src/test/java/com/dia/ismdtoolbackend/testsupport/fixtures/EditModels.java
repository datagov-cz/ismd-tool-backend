package com.dia.ismdtoolbackend.testsupport.fixtures;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;

import java.util.function.Consumer;

/**
 * Concept edit models for tests. Every field except the concept type starts {@code null}, which
 * the editor reads as "leave unchanged"; set only what the test edits.
 *
 * <pre>{@code
 * EditModels.trida(m -> m.setNameModel(Texts.name("cs", "Nové jméno")));
 * }</pre>
 */
public final class EditModels {

    private EditModels() {
    }

    public static ClassConceptEditModel trida() {
        ClassConceptEditModel model = new ClassConceptEditModel();
        model.setConceptType("TRIDA");
        return model;
    }

    public static ClassConceptEditModel trida(Consumer<ClassConceptEditModel> fields) {
        ClassConceptEditModel model = trida();
        fields.accept(model);
        return model;
    }

    public static PropertyConceptEditModel vlastnost() {
        PropertyConceptEditModel model = new PropertyConceptEditModel();
        model.setConceptType("VLASTNOST");
        return model;
    }

    public static PropertyConceptEditModel vlastnost(Consumer<PropertyConceptEditModel> fields) {
        PropertyConceptEditModel model = vlastnost();
        fields.accept(model);
        return model;
    }

    public static RelationshipConceptEditModel vztah() {
        RelationshipConceptEditModel model = new RelationshipConceptEditModel();
        model.setConceptType("VZTAH");
        return model;
    }

    public static RelationshipConceptEditModel vztah(Consumer<RelationshipConceptEditModel> fields) {
        RelationshipConceptEditModel model = vztah();
        fields.accept(model);
        return model;
    }
}
