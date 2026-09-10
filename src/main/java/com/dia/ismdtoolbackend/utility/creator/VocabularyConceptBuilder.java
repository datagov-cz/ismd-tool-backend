package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiConceptReferenceDto;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.utility.validation.ConceptCreateValidator;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;

import java.net.URI;
import java.util.*;

/** Converts selected draft terms to the existing concept-create models, without writing anything. */
public final class VocabularyConceptBuilder {
    private VocabularyConceptBuilder() {}

    public record PreparedConcepts(List<ConceptCreateModel> concepts, Map<String, String> conceptIris) {}

    public static PreparedConcepts prepare(OntologyCreateWithConceptsRequestDto request, String graph, String userId) {
        if (request.classes().size() + request.attributes().size() + request.relationships().size() > 1000) {
            throw invalid("Najednou lze vytvořit nejvýše 1000 pojmů.");
        }
        Map<String, ConceptCreateModel> models = new LinkedHashMap<>();
        Set<String> classRefs = new HashSet<>();
        for (var c : request.classes()) {
            ClassConceptModel model = new ClassConceptModel();
            model.setConceptType("TRIDA");
            model.setType(switch (c.type()) {
                case CLASS -> "třída";
                case SUBJECT -> "subjekt práva";
                case OBJECT -> "objekt práva";
            });
            add(models, c.ref(), model, graph, c.name(), c.definition(), c.explanation(), c.legalAct(), userId);
            classRefs.add(c.ref());
        }
        for (var a : request.attributes()) {
            PropertyConceptModel model = new PropertyConceptModel();
            model.setConceptType("VLASTNOST");
            model.setDataType(a.dataType());
            add(models, a.ref(), model, graph, a.name(), a.definition(), a.explanation(), a.legalAct(), userId);
        }
        for (var r : request.relationships()) {
            RelationshipConceptModel model = new RelationshipConceptModel();
            model.setConceptType("VZTAH");
            add(models, r.ref(), model, graph, r.name(), r.definition(), r.explanation(), r.legalAct(), userId);
        }

        // Allocate all IRIs first so references can point forward to any selected class.
        URIGenerator generator = new URIGenerator();
        generator.setEffectiveNamespace(UtilityMethods.ensureNamespaceEndsWithDelimiter(graph));
        Map<String, String> iris = new LinkedHashMap<>();
        Set<String> usedIris = new HashSet<>(Set.of(graph, graph + "/casovy-okamzik-vytvoreni"));
        models.forEach((ref, model) -> {
            String iri = generator.generateConceptURI(model.getNameModel().getName().get("cs"), null);
            if (!UtilityMethods.isValidIRI(iri)) throw invalid("Neplatné IRI pojmu " + ref + ": " + iri);
            if (!usedIris.add(iri)) throw invalid("Více pojmů má stejné výsledné IRI: " + iri + ". Upravte jejich názvy.");
            iris.put(ref, iri);
            model.setIdentifier(iri);
        });

        for (var c : request.classes()) {
            List<String> parents = new ArrayList<>();
            if (c.specializes() != null) {
                for (var parent : c.specializes()) {
                    String iri = resolveClass(parent, classRefs, iris, graph);
                    if (iri.equals(iris.get(c.ref()))) throw invalid("Třída " + c.ref() + " nemůže specializovat sama sebe.");
                    parents.add(iri);
                }
            }
            ((ClassConceptModel) models.get(c.ref())).setBroaderConcept(parents);
        }
        rejectSpecializationCycles(models);
        for (var a : request.attributes()) {
            ((PropertyConceptModel) models.get(a.ref())).setDomain(resolveClass(a.associatedClass(), classRefs, iris, graph));
        }
        for (var r : request.relationships()) {
            RelationshipConceptModel model = (RelationshipConceptModel) models.get(r.ref());
            model.setDomain(resolveClass(r.sourceClass(), classRefs, iris, graph));
            model.setRange(resolveClass(r.targetClass(), classRefs, iris, graph));
        }
        return new PreparedConcepts(List.copyOf(models.values()), Collections.unmodifiableMap(iris));
    }

    private static void add(Map<String, ConceptCreateModel> models, String ref, ConceptCreateModel model,
                            String graph, Map<String, String> name, Map<String, String> definition,
                            Map<String, String> explanation, String legalAct, String userId) {
        if (ref == null || ref.isBlank() || isAbsoluteIri(ref)) throw invalid("Neplatná dočasná reference: " + ref);
        if (models.putIfAbsent(ref, model) != null) throw invalid("Duplicitní reference: " + ref);
        NameModel nameModel = new NameModel();
        nameModel.setName(name);
        DefinitionModel definitionModel = new DefinitionModel();
        definitionModel.setDefinition(definition);
        DescriptionModel descriptionModel = new DescriptionModel();
        descriptionModel.setDescription(explanation);
        model.setNameModel(nameModel);
        model.setDefinitionModel(definitionModel);
        model.setDescriptionModel(descriptionModel);
        model.setOntologyGraphName(graph);
        // AI uses the public e-Sbírka ELI host; ordinary writes store the same ELI path in open-data form.
        if (legalAct != null && legalAct.startsWith("https://e-sbirka.gov.cz/eli/")) {
            legalAct = SparqlIriValidator.esbirkaDomain() + "/esel-esb" + legalAct.substring("https://e-sbirka.gov.cz".length());
        }
        model.setDefiningLegalSource(legalAct == null ? List.of() : List.of(legalAct));
        ConceptCreateValidator.validate(model, userId);
    }

    private static String resolveClass(AiConceptReferenceDto reference, Set<String> classRefs,
                                       Map<String, String> iris, String graph) {
        if (reference == null || (reference.ref() == null) == (reference.iri() == null)) {
            throw invalid("Vazba musí obsahovat právě jedno z ref nebo iri.");
        }
        if (reference.ref() != null) {
            if (!classRefs.contains(reference.ref())) throw invalid("Vazba odkazuje na nevybranou nebo neexistující třídu: " + reference.ref());
            return iris.get(reference.ref());
        }
        String iri = reference.iri();
        if (iri.isBlank() || !UtilityMethods.isValidIRI(iri) || !isAbsoluteIri(iri)) throw invalid("Neplatné IRI cílové třídy: " + iri);
        if (iri.equals(graph) || iri.startsWith(UtilityMethods.ensureNamespaceEndsWithDelimiter(graph))) {
            throw invalid("Na nové třídy odkazujte pomocí ref: " + iri);
        }
        // Existing external IRI references follow the ordinary concept-create rules.
        return iri;
    }

    private static void rejectSpecializationCycles(Map<String, ConceptCreateModel> models) {
        Map<String, List<String>> parents = new HashMap<>();
        for (var model : models.values()) {
            if (model instanceof ClassConceptModel c) parents.put(c.getIdentifier(), c.getBroaderConcept());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String iri : parents.keySet()) visit(iri, parents, visiting, visited);
    }

    private static void visit(String iri, Map<String, List<String>> parents, Set<String> visiting, Set<String> visited) {
        if (!parents.containsKey(iri) || visited.contains(iri)) return;
        if (!visiting.add(iri)) throw invalid("Specializace tříd tvoří cyklus.");
        for (String parent : parents.get(iri)) visit(parent, parents, visiting, visited);
        visiting.remove(iri);
        visited.add(iri);
    }

    private static boolean isAbsoluteIri(String value) {
        try { return URI.create(value).isAbsolute(); }
        catch (IllegalArgumentException e) { return false; }
    }

    private static ConceptValidationException invalid(String message) {
        return new ConceptValidationException(message);
    }
}
