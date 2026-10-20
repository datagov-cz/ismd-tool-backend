package com.dia.ismdtoolbackend.utility.creator;

import com.dia.utility.URIGenerator;
import org.apache.jena.ontology.OntModel;

/** The working model and IRI generator of one concept build. */
record ConceptBuildContext(OntModel ontModel, URIGenerator uriGenerator) {
}
