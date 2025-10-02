package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import org.apache.jena.ontology.OntologyException;

@Data
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "conceptType",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClassConceptModel.class, name = "TRIDA"),
        @JsonSubTypes.Type(value = PropertyConceptModel.class, name = "VLASTNOST"),
        @JsonSubTypes.Type(value = RelationshipConceptModel.class, name = "VZTAH")
})
public abstract class ConceptCreateModel {
    protected String conceptType;
    protected String namespace;
    protected String conceptName;
    protected String identifier;
    protected String altName;
    protected String description;
    protected String definition;
    protected String definingNonLegalSource;
    protected String definingLegalSource;
    protected String relatedNonLegalSource;
    protected String relatedLegalSource;
    protected String exactMatch;
    protected String inTezaurus;

    public abstract ConceptType getConceptTypeEnum();

    public void validate() {
        if (conceptType == null || conceptType.trim().isEmpty()) {
            throw new OntologyException("Typ pojmu je povinný");
        }
        if (conceptName == null || conceptName.trim().isEmpty()) {
            throw new OntologyException("Název pojmu je povinný");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new OntologyException("Popis pojmu je povinný");
        }

        validateSpecificFields();
    }

    protected abstract void validateSpecificFields();
}
