package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.NotBlank;

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
    @NotBlank
    protected NameModel nameModel;
    protected String identifier;
    protected List<AltNameModel> altNameModel;
    protected DescriptionModel descriptionModel;
    protected DefinitionModel definitionModel;
    protected String definingNonLegalSource;
    protected String definingLegalSource;
    protected String relatedNonLegalSource;
    protected String relatedLegalSource;
    protected String exactMatch;
    protected String inTezaurus;

    public abstract ConceptType getConceptTypeEnum();

    protected abstract void validateSpecificFields();
}
