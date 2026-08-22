package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.utility.DataTypeConverter;
import com.dia.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

@Slf4j
class ConceptFieldUpdaters {

    private static final String TYPE = "type";
    private static final String AGENDA_CODE = "agendaCode";
    private static final String AIS = "agendaSystemCode";
    private static final String IS_PUBLIC = "isPublic";
    private static final String IN_TEZAURUS = "inTezaurus";

    private final ConceptIriFactory iriFactory;

    ConceptFieldUpdaters(ConceptIriFactory iriFactory) {
        this.iriFactory = iriFactory;
    }

    /**
     * Applies the class {@code type} value (TSP/TOP rdf:type transitions).
     * Thin package-private entry point for {@link ClassConceptTypeEditor}, keeping
     * the string-dispatch table ({@link #updateStringProperty}) an internal detail.
     */
    void updateClassTypeProperty(Resource newConcept, String newType, Resource oldConcept,
                                 Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        updateStringProperty(newConcept, TYPE, newType, oldConcept, model, toRemove, toAdd);
    }

    void editCommonFields(ConceptEditModel editModel, ConceptEditor.EditContext context,
                                   Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        updateNameModel(context.newConcept, editModel.getNameModel(), context.oldConcept, model, toRemove, toAdd);
        updateDescriptionModel(context.newConcept, editModel.getDescriptionModel(), context.oldConcept, model, toRemove, toAdd);
        updateDefinitionModel(context.newConcept, editModel.getDefinitionModel(), context.oldConcept, model, toRemove, toAdd);
        updateAltNameModel(context.newConcept, editModel.getAltNameModel(), context.oldConcept, model, toRemove, toAdd);
        updateLegalSources(context.newConcept, editModel, context.oldConcept, model, toRemove, toAdd);
        updateNonLegalSources(context.newConcept, editModel, context.oldConcept, model, toRemove, toAdd);
        updateExactMatch(context.newConcept, editModel.getExactMatch(), context.oldConcept, model, toRemove, toAdd);
        updateBooleanProperty(context.newConcept, IN_TEZAURUS, editModel.getInTezaurus(), context.oldConcept, model, toRemove, toAdd);
    }

    private void updateNameModel(Resource newConcept, NameModel nameModel, Resource oldConcept,
                                 Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (nameModel == null || nameModel.getName() == null) return;

        // Name is required: an empty incoming map is a no-op (never a clear-all),
        // which the merge produces naturally (merged == existing).
        Map<String, String> existing = RdfLangValues.byLanguage(oldConcept, SKOS.prefLabel);
        applyMergedLangProperty(newConcept, SKOS.prefLabel, existing, nameModel.getName(),
                model, toRemove, toAdd);
    }

    private void updateDescriptionModel(Resource newConcept, DescriptionModel descModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (descModel == null) return;

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        Map<String, String> existing = RdfLangValues.byLanguage(oldConcept, descProperty);
        Map<String, String> incoming = descModel.getDescription();

        // An empty/absent incoming map clears the field entirely.
        if (incoming == null || incoming.isEmpty()) {
            if (!existing.isEmpty()) {
                removeAllByPredicate(newConcept, descProperty, toRemove, toAdd);
            }
            return;
        }

        applyMergedLangProperty(newConcept, descProperty, existing, incoming, model, toRemove, toAdd);
    }

    private void updateDefinitionModel(Resource newConcept, DefinitionModel defModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (defModel == null) return;

        Map<String, String> existing = RdfLangValues.byLanguage(oldConcept, SKOS.definition);
        Map<String, String> incoming = defModel.getDefinition();

        // An empty/absent incoming map clears the field entirely.
        if (incoming == null || incoming.isEmpty()) {
            if (!existing.isEmpty()) {
                removeAllByPredicate(newConcept, SKOS.definition, toRemove, toAdd);
            }
            return;
        }

        applyMergedLangProperty(newConcept, SKOS.definition, existing, incoming, model, toRemove, toAdd);
    }

    private void updateAltNameModel(Resource newConcept, AltNameModel altNameModel, Resource oldConcept,
                                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (altNameModel == null) return;

        Map<String, List<String>> oldAltNamesByLang = RdfLangValues.allByLanguage(oldConcept);

        Map<String, List<String>> newAltNamesByLang = new HashMap<>();
        if (altNameModel.getAltName() != null && !altNameModel.getAltName().isEmpty()) {
            for (Map.Entry<String, List<String>> entry : altNameModel.getAltName().entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                    ? entry.getKey()
                    : DEFAULT_LANG;
                for (String value : entry.getValue()) {
                    if (value != null && !value.trim().isEmpty()) {
                        newAltNamesByLang.computeIfAbsent(languageTag, k -> new ArrayList<>())
                                .add(value.trim());
                    }
                }
            }
            // Sorted to match allByLanguage's ordering, so the comparison below is order-insensitive.
            newAltNamesByLang.values().forEach(Collections::sort);
        }

        if (!oldAltNamesByLang.equals(newAltNamesByLang)) {
            removeAllByPredicate(newConcept, SKOS.altLabel, toRemove, toAdd);
            for (Map.Entry<String, List<String>> entry : newAltNamesByLang.entrySet()) {
                for (String value : entry.getValue()) {
                    toAdd.add(model.createStatement(newConcept, SKOS.altLabel,
                            model.createLiteral(value, entry.getKey())));
                }
            }
        }
    }

    private void updateLegalSources(Resource newConcept, ConceptEditModel editModel, Resource oldConcept,
                                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Property definingProp = model.createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
        Property relatedProp = model.createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

        updateLegalSourceList(newConcept, definingProp, editModel.getDefiningLegalSource(),
                oldConcept, model, toRemove, toAdd);
        updateLegalSourceList(newConcept, relatedProp, editModel.getRelatedLegalSource(),
                oldConcept, model, toRemove, toAdd);
    }

    private void updateNonLegalSources(Resource newConcept, ConceptEditModel editModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Property definingProp = model.createProperty(iriFactory.getEffectiveNamespace() + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Property relatedProp = model.createProperty(iriFactory.getEffectiveNamespace() + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);

        updateNonLegalSourceList(newConcept, definingProp, editModel.getDefiningNonLegalSource(),
                oldConcept, model, toRemove, toAdd);
        updateNonLegalSourceList(newConcept, relatedProp, editModel.getRelatedNonLegalSource(),
                oldConcept, model, toRemove, toAdd);
    }

    private void updateExactMatch(Resource newConcept, List<String> exactMatchList, Resource oldConcept,
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

    private void updateStringProperty(Resource newConcept, String propertyName, String newValue,
                                       Resource oldConcept, Model model, Set<Statement> toRemove,
                                       Set<Statement> toAdd) {
        switch (propertyName) {
            case TYPE -> updateClassType(newConcept, newValue, oldConcept, model, toRemove, toAdd);
            case AGENDA_CODE -> updateAgenda(newConcept, newValue, oldConcept, model, toRemove, toAdd);
            case AIS -> updateAIS(newConcept, newValue, oldConcept, model, toRemove, toAdd);
            default -> log.warn("Unknown string property: {}", propertyName);
        }
    }

    private void updateClassType(Resource newConcept, String newType, Resource oldConcept,
                                  Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (newType == null) return;

        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);

        boolean oldHasTSP = oldConcept.hasProperty(RDF.type, tspType);
        boolean oldHasTOP = oldConcept.hasProperty(RDF.type, topType);

        boolean newHasTSP = newType.toLowerCase().contains("subjekt");
        boolean newHasTOP = newType.toLowerCase().contains("objekt");

        if (oldHasTSP && !newHasTSP) {
            removeTypeStatement(newConcept, oldConcept, tspType, model, toRemove, toAdd);
        }
        if (oldHasTOP && !newHasTOP) {
            removeTypeStatement(newConcept, oldConcept, topType, model, toRemove, toAdd);
        }
        if (!oldHasTSP && newHasTSP) {
            toAdd.add(model.createStatement(newConcept, RDF.type, tspType));
        }
        if (!oldHasTOP && newHasTOP) {
            toAdd.add(model.createStatement(newConcept, RDF.type, topType));
        }
    }

    /**
     * Removes a specific {@code rdf:type} value from the concept. Stages removal
     * of the statement under the old IRI (still present in the model) AND strips
     * any copy of it staged under the new IRI by {@code renameConceptIRI} — so a
     * type that's being dropped does not survive a rename via the copy-all pass.
     */
    private void removeTypeStatement(Resource newConcept, Resource oldConcept, Resource typeValue,
                                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        toRemove.add(model.createStatement(oldConcept, RDF.type, typeValue));
        toAdd.removeIf(stmt ->
                stmt.getSubject().equals(newConcept) &&
                        stmt.getPredicate().equals(RDF.type) &&
                        stmt.getObject().equals(typeValue));
    }

    private void updateAgenda(Resource newConcept, String agendaCode, Resource oldConcept,
                              Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (agendaCode == null) return;

        Property agendaProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);

        if (agendaCode.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, agendaProperty, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, agendaProperty, toRemove, toAdd);

        if (UtilityMethods.isValidAgendaValue(agendaCode)) {
            String transformed = UtilityMethods.transformAgendaValue(agendaCode);
            if (DataTypeConverter.isUri(transformed)) {
                toAdd.add(model.createStatement(newConcept, agendaProperty, model.createResource(transformed)));
            } else {
                Literal typedLiteral = DataTypeConverter.createTypedLiteral(transformed, model, null, AGENDA_LONG);
                toAdd.add(model.createStatement(newConcept, agendaProperty, typedLiteral));
            }
        }
    }

    private void updateAIS(Resource newConcept, String aisCode, Resource oldConcept,
                           Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (aisCode == null) return;

        Property aisProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + UDAJE_AIS);

        if (aisCode.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, aisProperty, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, aisProperty, toRemove, toAdd);

        if (UtilityMethods.isValidAISValue(aisCode)) {
            String transformed = UtilityMethods.transformAISValue(aisCode);
            if (DataTypeConverter.isUri(transformed)) {
                toAdd.add(model.createStatement(newConcept, aisProperty, model.createResource(transformed)));
            } else {
                Literal typedLiteral = DataTypeConverter.createTypedLiteral(transformed, model, null, UDAJE_AIS);
                toAdd.add(model.createStatement(newConcept, aisProperty, typedLiteral));
            }
        }
    }

    void updatePrivacyProvisionsList(Resource newConcept, List<String> privacyProvisions,
                                             Resource oldConcept, Model model, Set<Statement> toRemove,
                                             Set<Statement> toAdd) {
        if (privacyProvisions == null) return;

        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        Set<String> oldProvisions = getResourceURIs(oldConcept, provisionProperty);
        Set<String> newProvisions = new HashSet<>();

        for (String provision : privacyProvisions) {
            if (provision == null || provision.trim().isEmpty()) continue;
            String canonical = EsbirkaEliParser.canonicalizeHost(provision.trim());
            if (SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                newProvisions.add(canonical);
            } else {
                log.warn("Skipping privacy provision — not a canonical e-Sbírka ELI IRI: {}", provision.trim());
            }
        }

        if (privacyProvisions.isEmpty() || newProvisions.isEmpty()) {
            if (!oldProvisions.isEmpty()) {
                removeAllByPredicate(newConcept, provisionProperty, toRemove, toAdd);
            }
        } else if (!oldProvisions.equals(newProvisions)) {
            removeAllByPredicate(newConcept, provisionProperty, toRemove, toAdd);
            for (String provisionURI : newProvisions) {
                toAdd.add(model.createStatement(newConcept, provisionProperty, model.createResource(provisionURI)));
            }
        }
    }

    private void updateGovernanceProperty(Resource newConcept, String newValue, String propertyName,
                                           Resource oldConcept, Model model, Set<Statement> toRemove,
                                           Set<Statement> toAdd) {
        if (newValue == null) return;

        Property property = model.createProperty(OFN_NAMESPACE + propertyName);
        String oldIRI = getResourceURI(oldConcept, property);

        if (newValue.trim().isEmpty()) {
            if (oldIRI != null) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
            return;
        }

        String newIRI = iriFactory.generateGovernanceIRI(newValue, propertyName);

        if (!Objects.equals(oldIRI, newIRI)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            if (newIRI != null) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(newIRI)));
            }
        }
    }

    private void updateSharingMethodList(Resource newConcept, List<String> newValues,
                                              Resource oldConcept, Model model, Set<Statement> toRemove,
                                               Set<Statement> toAdd) {
        if (newValues == null) return;

        Property property = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        Set<String> oldIRIs = getResourceURIs(oldConcept, property);
        Set<String> newIRIs = new HashSet<>();

        for (String value : newValues) {
            if (value != null && !value.trim().isEmpty()) {
                String newIRI = iriFactory.generateGovernanceIRI(value, ZPUSOB_SDILENI);
                if (newIRI != null) {
                    newIRIs.add(newIRI);
                }
            }
        }

        if (newValues.isEmpty() || newIRIs.isEmpty()) {
            if (!oldIRIs.isEmpty()) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
        } else if (!oldIRIs.equals(newIRIs)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            for (String iri : newIRIs) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(iri)));
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

        String newRangeURI = DataTypeConverter.getXSDTypeURI(dataType.trim());

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

    private void updateBooleanProperty(Resource newConcept, String propertyName, Boolean newValue,
                                       Resource oldConcept, Model model, Set<Statement> toRemove,
                                       Set<Statement> toAdd) {
        if (newValue == null) return;

        Property property = switch (propertyName) {
            case JE_PPDF_LONG -> model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
            case IS_PUBLIC -> model.createProperty(OFN_NAMESPACE_VS + JE_VEREJNY);
            case IN_TEZAURUS -> model.createProperty(iriFactory.getEffectiveNamespace() + IN_TEZAURUS);
            default -> {
                log.warn("Unknown boolean property: {}", propertyName);
                yield null;
            }
        };

        if (property == null) return;

        String oldValue = getPropertyValue(oldConcept, property);
        String newValueStr = newValue.toString();

        if (!Objects.equals(oldValue, newValueStr)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            toAdd.add(model.createStatement(newConcept, property, model.createLiteral(newValueStr)));
        }
    }

    private void updateLegalSourceList(Resource newConcept, Property property, List<String> newSources,
                                        Resource oldConcept, Model model, Set<Statement> toRemove,
                                        Set<Statement> toAdd) {
        if (newSources == null) return;

        Set<String> oldSourceURIs = getResourceURIs(oldConcept, property);
        Set<String> newSourceURIs = new HashSet<>();

        for (String source : newSources) {
            if (source == null || source.trim().isEmpty()) continue;
            String canonical = EsbirkaEliParser.canonicalizeHost(source.trim());
            if (SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                newSourceURIs.add(canonical);
            } else {
                log.warn("Skipping legal source — not a canonical e-Sbírka ELI IRI: {}", source.trim());
            }
        }

        if (newSources.isEmpty() || newSourceURIs.isEmpty()) {
            if (!oldSourceURIs.isEmpty()) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
            return;
        }

        if (!oldSourceURIs.equals(newSourceURIs)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            for (String sourceURI : newSourceURIs) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(sourceURI)));
            }
        }
    }

    private void updateNonLegalSourceList(Resource newConcept, Property property, List<DigitalObjectModel> newSources,
                                           Resource oldConcept, Model model, Set<Statement> toRemove,
                                           Set<Statement> toAdd) {
        if (newSources == null) return;

        List<DigitalObjectModel> validSources = newSources.stream()
                .filter(this::isValidDigitalObject)
                .toList();

        long skipped = newSources.stream().filter(d -> !isValidDigitalObject(d)).count();
        if (skipped > 0) {
            log.warn("Skipped {} non-legal source(s) — url is blank or invalid", skipped);
        }

        if (validSources.isEmpty()) {
            removeAllByPredicate(oldConcept, property, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, property, toRemove, toAdd);

        Resource digitalObjectType = model.createResource(DIGITALNI_OBJEKT);
        Property schemaUrlProperty = model.createProperty(SCHEMA_URL);
        Property dctermsTitle = model.createProperty(DCT_NS + "title");
        Property dctermsDescription = model.createProperty(DCT_NS + "description");

        for (DigitalObjectModel source : validSources) {
            Resource digitalDocument = model.createResource();

            toAdd.add(model.createStatement(digitalDocument, RDF.type, digitalObjectType));
            toAdd.add(model.createStatement(digitalDocument, schemaUrlProperty,
                    model.createResource(source.getUrl().trim())));

            if (source.getName() != null && !source.getName().trim().isEmpty()) {
                toAdd.add(model.createStatement(digitalDocument, dctermsTitle,
                        model.createLiteral(source.getName().trim(), DEFAULT_LANG)));
            }
            if (source.getDescription() != null && !source.getDescription().trim().isEmpty()) {
                toAdd.add(model.createStatement(digitalDocument, dctermsDescription,
                        model.createLiteral(source.getDescription().trim(), DEFAULT_LANG)));
            }

            toAdd.add(model.createStatement(newConcept, property, digitalDocument));
        }
    }

    private boolean isValidDigitalObject(DigitalObjectModel source) {
        if (source == null) return false;
        String url = source.getUrl();
        if (url == null || url.trim().isEmpty()) return false;
        return UtilityMethods.isValidUrl(url.trim());
    }

    /**
     * Merges an incoming {@code lang -> value} map into the existing values of a
     * language-tagged literal property and, if the result differs, rewrites the
     * whole property. This is the shared MERGE semantics for prefLabel /
     * description / definition (an edit touching one language preserves the
     * others). A blank/empty incoming value deletes that language tag; a blank
     * tag falls back to {@link com.dia.constants.ExportConstants.Common#DEFAULT_LANG}.
     *
     * <p>NOTE: callers decide what an empty/absent incoming map means. Name is
     * required and never cleared; description/definition clear-all on an empty
     * map before calling this. altLabel is intentionally NOT routed here — it is
     * full-replace, not merge.
     */
    private void applyMergedLangProperty(Resource newConcept, Property property,
                                         Map<String, String> existing, Map<String, String> incoming,
                                         Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Map<String, String> merged = new HashMap<>(existing);
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), entry.getValue().trim());
            }
        }

        if (existing.equals(merged)) return;

        removeAllByPredicate(newConcept, property, toRemove, toAdd);
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                    ? entry.getKey()
                    : DEFAULT_LANG;
            toAdd.add(model.createStatement(newConcept, property,
                    model.createLiteral(entry.getValue(), languageTag)));
        }
    }

    private String getPropertyValue(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        return stmt != null && stmt.getObject().isLiteral() ? stmt.getObject().asLiteral().getString() : null;
    }

    private String getResourceURI(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        return stmt != null && stmt.getObject().isResource() ? stmt.getObject().asResource().getURI() : null;
    }

    private Set<String> getResourceURIs(Resource resource, Property property) {
        Set<String> uris = new HashSet<>();
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isResource()) {
                uris.add(stmt.getObject().asResource().getURI());
            }
        }
        return uris;
    }

    private void removeAllByPredicate(Resource resource, Property property, Set<Statement> toRemove, Set<Statement> toAdd) {
        RdfLangValues.removeAllByPredicate(resource, property, toRemove, toAdd);
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

    void updateDataClassification(Resource newConcept, Boolean isPublic, List<String> privacyProvisions,
                                          Resource oldConcept, Model model, Set<Statement> toRemove,
                                          Set<Statement> toAdd) {
        Resource verejnyLegal = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        Resource neverejnyLegal = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);

        if (oldConcept.hasProperty(RDF.type, verejnyLegal)) {
            removeTypeStatement(newConcept, oldConcept, verejnyLegal, model, toRemove, toAdd);
        }
        if (oldConcept.hasProperty(RDF.type, neverejnyLegal)) {
            removeTypeStatement(newConcept, oldConcept, neverejnyLegal, model, toRemove, toAdd);
        }
        boolean hasNonEmptyProvisions = privacyProvisions != null &&
                privacyProvisions.stream().anyMatch(p -> p != null && !p.trim().isEmpty());

        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        boolean hasValidProvisions = toAdd.stream().anyMatch(stmt ->
                stmt.getSubject().equals(newConcept) &&
                stmt.getPredicate().equals(provisionProperty));
        if (Boolean.TRUE.equals(isPublic)) {
            if (!hasNonEmptyProvisions) {
                toAdd.add(model.createStatement(newConcept, RDF.type, verejnyLegal));
            }
        } else if (Boolean.FALSE.equals(isPublic)) {
            if (!hasValidProvisions) {
                return;
            }
            toAdd.add(model.createStatement(newConcept, RDF.type, neverejnyLegal));
        }
    }

    void updateSharedGovernanceMetadata(Resource newConcept, Boolean isInPPDF, String agendaCode,
                                                 String agendaSystemCode, List<String> sharingMethod,
                                                 String acquisitionMethod, String contentType,
                                                 Resource oldConcept, Model model, Set<Statement> toRemove,
                                                 Set<Statement> toAdd) {
        updateBooleanProperty(newConcept, JE_PPDF_LONG, isInPPDF, oldConcept, model, toRemove, toAdd);
        updateStringProperty(newConcept, AGENDA_CODE, agendaCode, oldConcept, model, toRemove, toAdd);
        updateStringProperty(newConcept, AIS, agendaSystemCode, oldConcept, model, toRemove, toAdd);
        updateGovernanceProperty(newConcept, contentType, TYP_OBSAHU, oldConcept, model, toRemove, toAdd);
        updateGovernanceProperty(newConcept, acquisitionMethod, ZPUSOB_ZISKANI, oldConcept, model, toRemove, toAdd);
        updateSharingMethodList(newConcept, sharingMethod, oldConcept, model, toRemove, toAdd);
    }

    /**
     * Rewrites the code-list structure: the číselník is a named subject carrying its type and
     * its NKOD dataset. Class concepts only.
     *
     * <p>The removal branch drops the old číselník's own statements as well as the link. Fully exhaustive.
     */
    void updateCodeListDataset(Resource newConcept, String newCodeListIri, String newDatasetUrl,
                                         Resource oldConcept, Model model,
                                         Set<Statement> toRemove, Set<Statement> toAdd) {
        if (newCodeListIri == null && newDatasetUrl == null) return;

        Property instanceDefinedByCodeList = model.createProperty(
                OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);

        if (oldConcept.hasProperty(instanceDefinedByCodeList)) {
            StmtIterator stmtIter = oldConcept.listProperties(instanceDefinedByCodeList);
            while (stmtIter.hasNext()) {
                Statement stmt = stmtIter.next();
                toRemove.add(stmt);
                if (stmt.getObject().isResource()) {
                    Resource codeListNode = stmt.getObject().asResource();
                    StmtIterator nodeIter = codeListNode.listProperties();
                    while (nodeIter.hasNext()) {
                        toRemove.add(nodeIter.next());
                    }
                }
            }
        }

        // Add the new structure. Both IRIs are present together or not at all —
        // ConceptInputValidator rejects the one-sided cases before this runs.
        if (newCodeListIri != null && !newCodeListIri.trim().isEmpty()
                && newDatasetUrl != null && !newDatasetUrl.trim().isEmpty()) {
            Resource codeListType = model.createResource(
                    OFN_NAMESPACE_LEGAL + CISELNIK);
            Property datasetProperty = model.createProperty(
                    OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);

            Resource codeListNode = model.createResource(newCodeListIri.trim());
            toAdd.add(model.createStatement(codeListNode, RDF.type, codeListType));
            toAdd.add(model.createStatement(codeListNode, datasetProperty,
                    model.createResource(newDatasetUrl.trim())));
            toAdd.add(model.createStatement(newConcept, instanceDefinedByCodeList, codeListNode));
        }
    }
}
