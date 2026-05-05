package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "conceptType",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClassConceptEditModel.class, name = "TRIDA"),
        @JsonSubTypes.Type(value = PropertyConceptEditModel.class, name = "VLASTNOST"),
        @JsonSubTypes.Type(value = RelationshipConceptEditModel.class, name = "VZTAH")
})
public abstract class ConceptEditModel {
    @NotBlank
    protected String conceptType;
    protected String namespace;
    protected NameModel nameModel;
    protected String identifier;
    protected AltNameModel altNameModel;
    protected DescriptionModel descriptionModel;
    protected DefinitionModel definitionModel;
    protected List<String> definingNonLegalSource;
    protected List<String> definingLegalSource;
    protected List<String> relatedNonLegalSource;
    protected List<String> relatedLegalSource;
    protected List<String> exactMatch;
    protected Boolean inTezaurus;

    public abstract ConceptType getConceptTypeEnum();

    protected abstract void validateSpecificFields();
}
