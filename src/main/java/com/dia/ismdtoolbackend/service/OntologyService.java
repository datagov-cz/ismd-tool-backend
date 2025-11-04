package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import org.apache.jena.ontology.OntologyException;

import java.util.List;

public interface OntologyService {
    void deleteOntology(Long ontologyId) throws OntologyException;
    OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId) throws OntologyException;
    GetOntologyDto getOntologyDetailModel(String ontologySlug) throws OntologyException;
    OntologyMetadataModel editOntology(OntologyEditModel ontologyEditModel) throws OntologyException;
    List<OntologyMetadataModel> getAll(String userId, Boolean isPublished) throws OntologyException;
}
