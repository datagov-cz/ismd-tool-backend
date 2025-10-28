package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import org.apache.jena.ontology.OntologyException;

public interface OntologyService {
    void deleteOntology(Long ontologyId);
    OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId);
    OntologyDetailModel getOntologyDetailModel(Long ontologyId);
    OntologyMetadataModel editOntology(OntologyEditModel ontologyEditModel, Long ontologyId);
}
