package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import org.apache.jena.ontology.OntologyException;

public interface OntologyService {
    void deleteOntology(Long ontologyId) throws OntologyException;
    OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId) throws OntologyException;
    OntologyMetadataModel editOntology(OntologyEditModel ontologyEditModel) throws OntologyException;
}
