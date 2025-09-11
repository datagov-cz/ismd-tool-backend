package com.dia.ismdtoolbackend.service;

import org.apache.jena.ontology.OntologyException;

public interface OntologyService {
    void deleteOntology(Long ontologyId) throws OntologyException;
}
