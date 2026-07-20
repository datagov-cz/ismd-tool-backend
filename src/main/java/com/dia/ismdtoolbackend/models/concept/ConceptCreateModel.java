package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        @JsonSubTypes.Type(value = ClassConceptModel.class, name = "TRIDA"),
        @JsonSubTypes.Type(value = PropertyConceptModel.class, name = "VLASTNOST"),
        @JsonSubTypes.Type(value = RelationshipConceptModel.class, name = "VZTAH")
})
public abstract class ConceptCreateModel {
    @NotBlank
    protected String ontologyGraphName;
    @NotBlank
    protected String conceptType;
    protected String namespace;
    @NotNull
    @Valid
    protected NameModel nameModel;
    protected String identifier;
    protected AltNameModel altNameModel;
    protected DescriptionModel descriptionModel;
    protected DefinitionModel definitionModel;
    protected List<DigitalObjectModel> definingNonLegalSource;
    protected List<String> definingLegalSource;
    protected List<DigitalObjectModel> relatedNonLegalSource;
    protected List<String> relatedLegalSource;
    protected List<String> exactMatch;
    protected Boolean inTezaurus;

    public abstract ConceptType getConceptTypeEnum();
}
