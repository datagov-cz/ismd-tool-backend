package com.dia.ismdtoolbackend.publication.file;

import com.dia.ismdtoolbackend.exception.EmptyDataException;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dia.ismdtoolbackend.publication.file.PublicationJson.*;

/**
 * Writes {@code slovniky.jsonld}: one graph node per vocabulary, being its OFN JSON-LD download merged
 * with its PostgreSQL metadata, then the publication record. Vocabularies are downloaded and written one
 * at a time, so only one vocabulary's content is in memory.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VocabularyFileWriter {

    public static final String FILE = "slovniky.jsonld";

    enum ContentStatus { OK, EMPTY, FAILED }

    private final OntologyDownloadService downloads;

    /** @return whether every vocabulary's content was written, i.e. none ended {@code FAILED} */
    public boolean write(PublicationSnapshot snapshot, PublicationHeader header, OutputStream out) {
        boolean complete = true;
        JsonGenerator generator = open(out, header);
        for (Ontology ontology : snapshot.ontologies()) {
            ObjectNode content = null;
            ContentStatus status;
            try {
                content = (ObjectNode) MAPPER.readTree(downloads.downloadOntology(ontology.id(), "json-ld"));
                status = ContentStatus.OK;
            } catch (EmptyDataException e) {
                status = ContentStatus.EMPTY;
            } catch (RuntimeException e) {
                log.warn("Publication: content of vocabulary {} failed, publishing metadata only", ontology.iri(), e);
                status = ContentStatus.FAILED;
                complete = false;
            }
            generator.writeTree(vocabularyNode(ontology, content, status, snapshot.excludedConceptIris()));
        }
        close(generator, header, FILE, complete);
        return complete;
    }

    static ObjectNode vocabularyNode(Ontology ontology, ObjectNode content, ContentStatus status,
                                     Set<String> excluded) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(IRI, ontology.iri());
        if (content != null && content.has(TYP)) {
            node.set(TYP, content.get(TYP));
        } else {
            node.putArray(TYP).add(SLOVNIK_TYPE);
        }
        if (content != null) {
            content.properties().forEach(field -> {
                if (!Set.of(CONTEXT, IRI, TYP, POJMY).contains(field.getKey())) {
                    node.set(field.getKey(), field.getValue());
                }
            });
        }
        node.put(SLUG, ontology.slug());
        node.put(JE_PUBLIKOVAN, ontology.published());
        if (ontology.lastValidationStatus() != null) {
            node.put(STAV_POSLEDNI_VALIDACE, ontology.lastValidationStatus().name());
        }
        putIfPresent(node, OKAMZIK_POSLEDNI_VALIDACE, instant(ontology.lastValidationAt()));
        putIfPresent(node, VYTVORENO_V_ISMD, instant(ontology.createdAt()));
        putIfPresent(node, AKTUALIZOVANO_V_ISMD, instant(ontology.updatedAt()));
        node.put(STAV_OBSAHU, status.name());
        node.put(POCET_POJMU, ontology.concepts().size());
        if (content != null && content.get(POJMY) instanceof ArrayNode pojmy) {
            node.set(POJMY, concepts(pojmy, ontology.concepts(), excluded));
        }
        return node;
    }

    /**
     * Drops excluded concepts, strips references to them from the rest, and adds each remaining concept's
     * PostgreSQL metadata. A concept in RDF without a PostgreSQL row is kept as downloaded.
     */
    private static ArrayNode concepts(ArrayNode pojmy, List<Concept> metadata, Set<String> excluded) {
        Map<String, Concept> byIri = metadata.stream().collect(Collectors.toMap(Concept::iri, Function.identity(),
                (first, second) -> first));
        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode element : pojmy) {
            if (!(element instanceof ObjectNode pojem) || excluded.contains(pojem.path(IRI).asString(""))) {
                continue;
            }
            stripReferences(pojem, excluded);
            Concept concept = byIri.get(pojem.path(IRI).asString(""));
            if (concept != null) {
                pojem.put(SLUG, concept.slug());
                pojem.put(JE_PUBLIKOVAN, concept.published());
                putIfPresent(pojem, VYTVORENO_V_ISMD, instant(concept.createdAt()));
                putIfPresent(pojem, AKTUALIZOVANO_V_ISMD, instant(concept.updatedAt()));
            }
            result.add(pojem);
        }
        return result;
    }

    /** Removes IRI values naming an excluded concept; a list left empty is removed with its key. */
    static void stripReferences(ObjectNode pojem, Set<String> excluded) {
        if (excluded.isEmpty()) {
            return;
        }
        for (String key : new ArrayList<>(pojem.propertyNames())) {
            if (IRI.equals(key) || TYP.equals(key)) {
                continue;
            }
            JsonNode value = pojem.get(key);
            if (value.isString() && excluded.contains(value.asString())) {
                pojem.remove(key);
            } else if (value instanceof ArrayNode array) {
                for (int i = array.size() - 1; i >= 0; i--) {
                    if (array.get(i).isString() && excluded.contains(array.get(i).asString())) {
                        array.remove(i);
                    }
                }
                if (array.isEmpty()) {
                    pojem.remove(key);
                }
            }
        }
    }
}
