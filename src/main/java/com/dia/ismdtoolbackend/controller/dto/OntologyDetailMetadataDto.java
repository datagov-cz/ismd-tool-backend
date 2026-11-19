package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

/** The vocabulary detail's metadata: the summary plus its owner, comments and concept rows. */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class OntologyDetailMetadataDto extends OntologySummaryDto {
    private UserModel user;
    private List<CommentModel> comments;
    private List<ConceptMetadataModel> concepts;
}
