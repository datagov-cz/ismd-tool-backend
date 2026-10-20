package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.utility.DataTypeConverter;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDFS;

import java.util.*;

import static com.dia.ismdtoolbackend.utility.editor.ConceptEditStatements.*;
import static com.dia.ismdtoolbackend.utility.editor.RdfLangValues.removeAllByPredicate;

/** Edits the links of a concept to other resources: exact matches, hierarchy, domain and range. */
class ConceptRelationUpdater {

    private final ConceptIriFactory iriFactory;

    ConceptRelationUpdater(ConceptIriFactory iriFactory) {
        this.iriFactory = iriFactory;
    }

    void updateExactMatch(Resource newConcept, List<String> exactMatchList, Resource oldConcept,
                          Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (exactMatchList == null) return;

        Property exactMatchProp = model.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");
        Set<String> oldMatches = getResourceURIs(oldConcept, exactMatchProp);
        Set<String> newMatches = new HashSet<>();

        for (String iri : exactMatchList) {
            if (iri != null && !iri.trim().isEmpty()) {
                newMatches.add(iri.trim());
            }
        }

        if (exactMatchList.isEmpty() || newMatches.isEmpty()) {
            if (!oldMatches.isEmpty()) {
                removeAllByPredicate(newConcept, exactMatchProp, toRemove, toAdd);
            }
        } else if (!oldMatches.equals(newMatches)) {
            removeAllByPredicate(newConcept, exactMatchProp, toRemove, toAdd);
            for (String match : newMatches) {
                if (UtilityMethods.isValidIRI(match)) {
                    toAdd.add(model.createStatement(newConcept, exactMatchProp, model.createResource(match)));
                }
            }
        }
    }

    void updateBroaderConceptList(Resource newConcept, List<String> broaderConcept, Resource oldConcept,
                                       Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (broaderConcept == null) return;

        Property hierarchyProp = model.createProperty(iriFactory.getEffectiveNamespace() + "nadřazená-třída");
        Set<String> oldBroader = getResourceURIs(oldConcept, RDFS.subClassOf);
        Set<String> newBroader = parseBroaderConcepts(broaderConcept);

        if (broaderConcept.isEmpty() || newBroader.isEmpty()) {
            if (!oldBroader.isEmpty()) {
                removeAllByPredicate(newConcept, RDFS.subClassOf, toRemove, toAdd);
                removeAllByPredicate(newConcept, hierarchyProp, toRemove, toAdd);
            }
        } else if (!oldBroader.equals(newBroader)) {
            removeAllByPredicate(newConcept, RDFS.subClassOf, toRemove, toAdd);
            removeAllByPredicate(newConcept, hierarchyProp, toRemove, toAdd);
            for (String broader : newBroader) {
                toAdd.add(model.createStatement(newConcept, RDFS.subClassOf, model.createResource(broader)));
                toAdd.add(model.createStatement(newConcept, hierarchyProp, model.createResource(broader)));
            }
        }
    }

    void updateDomainRange(Resource newConcept, Property property, String newValue,
                                    Resource oldConcept, Model model, Set<Statement> toRemove,
                                    Set<Statement> toAdd) {
        if (newValue == null) return;

        String oldURI = getResourceURI(oldConcept, property);

        if (newValue.trim().isEmpty()) {
            if (oldURI != null) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
            return;
        }

        String newURI = iriFactory.isUri(newValue) ? newValue : iriFactory.generateConceptURI(newValue, null);

        if (!Objects.equals(oldURI, newURI)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            toAdd.add(model.createStatement(newConcept, property, model.createResource(newURI)));
        }
    }

    void updateDataTypeRange(Resource newConcept, String dataType, Resource oldConcept,
                                      Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (dataType == null) return;

        String oldRangeURI = getResourceURI(oldConcept, RDFS.range);

        if (dataType.trim().isEmpty()) {
            if (oldRangeURI != null) {
                removeAllByPredicate(newConcept, RDFS.range, toRemove, toAdd);
            }
            return;
        }

        String xsdTypeURI = DataTypeConverter.getXSDTypeURI(dataType.trim());
        String newRangeURI = xsdTypeURI != null ? xsdTypeURI : RDFS.Literal.getURI();

        if (!Objects.equals(oldRangeURI, newRangeURI)) {
            removeAllByPredicate(newConcept, RDFS.range, toRemove, toAdd);
            toAdd.add(model.createStatement(newConcept, RDFS.range, model.createResource(newRangeURI)));
        }
    }

    void updateSuperPropertyList(Resource newConcept, List<String> superProperties, Resource oldConcept,
                                          Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (superProperties == null) return;

        Set<String> oldSuperProps = getResourceURIs(oldConcept, RDFS.subPropertyOf);
        Set<String> newSuperProps = parseSuperProperties(superProperties);

        if (superProperties.isEmpty() || newSuperProps.isEmpty()) {
            if (!oldSuperProps.isEmpty()) {
                removeAllByPredicate(newConcept, RDFS.subPropertyOf, toRemove, toAdd);
            }
        } else if (!oldSuperProps.equals(newSuperProps)) {
            removeAllByPredicate(newConcept, RDFS.subPropertyOf, toRemove, toAdd);
            for (String superProp : newSuperProps) {
                toAdd.add(model.createStatement(newConcept, RDFS.subPropertyOf, model.createResource(superProp)));
            }
        }
    }

    private Set<String> parseSuperProperties(List<String> superProperties) {
        if (superProperties == null || superProperties.isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String prop : superProperties) {
            String trimmed = prop.trim();
            if (!trimmed.isEmpty()) {
                String uri = iriFactory.isUri(trimmed) ? trimmed :
                        iriFactory.generateConceptURI(trimmed, null);
                result.add(uri);
            }
        }
        return result;
    }

    private Set<String> parseBroaderConcepts(List<String> broaderConcept) {
        if (broaderConcept == null || broaderConcept.isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String concept : broaderConcept) {
            String trimmed = concept.trim();
            if (!trimmed.isEmpty()) {
                String uri = iriFactory.isUri(trimmed) ? trimmed :
                            iriFactory.generateConceptURI(trimmed, null);
                result.add(uri);
            }
        }
        return result;
    }
}
