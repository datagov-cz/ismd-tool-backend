package com.dia.ismdtoolbackend.utility.exporter.ofn;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

/**
 * Rewrites stored vocabulary RDF into OFN shapes. Accepts every shape the tool has written over time
 * (and OFN-native input such as NKD), so readers work before and after the data migration:
 * <ul>
 *   <li>legacy marker types {@code slovníky:třída/vlastnost/vztah} become the OWL kind and are removed;</li>
 *   <li>{@code slovníky:typ-*-práva} becomes {@code vsgov:typ-*-práva};</li>
 *   <li>provisions under any {@code …/ustanovení-dokládající-neveřejnost-údaje} predicate move to the OFN one;</li>
 *   <li>non-legal sources under any namespace move to {@code slovníky:}; {@code schema:url} becomes an
 *       {@code xsd:anyURI} literal;</li>
 *   <li>{@code dct:conformsTo} becomes {@code slovníky:definující-ustanovení};</li>
 *   <li>creation and change dates become a {@code čas:časový-okamžik} blank node with a zoned
 *       {@code xsd:dateTimeStamp} (or {@code xsd:date});</li>
 *   <li>a PPDF flag stored as text becomes {@code xsd:boolean};</li>
 *   <li>the internal {@code nadřazená-třída} duplicate, {@code inTezaurus} and the junk
 *       {@code <https://slovník.gov.cz/pojem>} class are removed;</li>
 *   <li>the vocabulary gets all three OFN types; {@code rdfs:label} fills a missing {@code skos:prefLabel}.</li>
 * </ul>
 * Mapping details are in {@code docs/JENA_TDB2_RDF_SCHEMA.md}.
 */
@Slf4j
public final class OfnStorageNormalizer {

    private OfnStorageNormalizer() {
    }

    /** A normalised copy; {@code source} is not changed. */
    public static Model normalizedCopy(Model source, String vocabularyIri) {
        Model copy = ModelFactory.createDefaultModel();
        copy.setNsPrefixes(source.getNsPrefixMap());
        copy.add(source);
        normalize(copy, vocabularyIri);
        return copy;
    }

    /** Normalises {@code model} in place. {@code vocabularyIri} may be null (types are then not added). */
    public static void normalize(Model model, String vocabularyIri) {
        removeJunkClass(model);
        replaceMarkerTypes(model);
        replaceType(model, OfnIris.LEGACY_TYP_SUBJEKTU_PRAVA, OfnIris.TYP_SUBJEKTU_PRAVA);
        replaceType(model, OfnIris.LEGACY_TYP_OBJEKTU_PRAVA, OfnIris.TYP_OBJEKTU_PRAVA);
        renamePredicates(model);
        normalizeUrls(model);
        normalizePpdf(model);
        LegacyDates.normalize(model);
        copyLabels(model);
        if (vocabularyIri != null) {
            Resource vocabulary = model.getResource(vocabularyIri);
            if (model.containsResource(vocabulary)) {
                for (String type : List.of(OfnIris.SLOVNIK, OfnIris.CONCEPT_SCHEME, OfnIris.ONTOLOGY)) {
                    vocabulary.addProperty(RDF.type, model.createResource(type));
                }
            }
        }
    }

    /** The OWL kind a legacy marker stands for. */
    static final Map<String, String> MARKER_TO_OWL = Map.of(
            OfnIris.LEGACY_TRIDA, OfnIris.OWL_CLASS,
            OfnIris.LEGACY_VLASTNOST, OfnIris.OWL_DATATYPE_PROPERTY,
            OfnIris.LEGACY_VZTAH, OfnIris.OWL_OBJECT_PROPERTY);

    /** Adds the OWL kind for each legacy marker and removes the markers. Returns the number of markers removed. */
    public static int replaceMarkerTypes(Model model) {
        int removed = 0;
        for (Map.Entry<String, String> marker : MARKER_TO_OWL.entrySet()) {
            Resource markerType = model.createResource(marker.getKey());
            Resource owlType = model.createResource(marker.getValue());
            for (Resource concept : model.listSubjectsWithProperty(RDF.type, markerType).toList()) {
                if (!hasOtherOwlKind(concept, marker.getValue())) {
                    concept.addProperty(RDF.type, owlType);
                }
                model.remove(concept, RDF.type, markerType);
                removed++;
            }
        }
        return removed;
    }

    private static boolean hasOtherOwlKind(Resource concept, String kind) {
        return MARKER_TO_OWL.values().stream()
                .filter(other -> !other.equals(kind))
                .anyMatch(other -> concept.hasProperty(RDF.type, concept.getModel().createResource(other)));
    }

    private static void removeJunkClass(Model model) {
        model.removeAll(model.createResource(OfnIris.LEGACY_JUNK_POJEM), null, null);
    }

    private static void replaceType(Model model, String legacy, String ofn) {
        Resource legacyType = model.createResource(legacy);
        for (Resource subject : model.listSubjectsWithProperty(RDF.type, legacyType).toList()) {
            model.remove(subject, RDF.type, legacyType);
            subject.addProperty(RDF.type, model.createResource(ofn));
        }
    }

    /** Moves legacy predicates to their OFN term, or drops internal ones. */
    private static void renamePredicates(Model model) {
        List<Statement> toRemove = new ArrayList<>();
        List<Statement> toAdd = new ArrayList<>();
        for (Statement statement : model.listStatements().toList()) {
            String predicate = statement.getPredicate().getURI();
            String target = ofnPredicateFor(predicate);
            if (target == null || target.equals(predicate)) {
                continue;
            }
            toRemove.add(statement);
            if (!target.isEmpty()) {
                toAdd.add(model.createStatement(statement.getSubject(), model.createProperty(target),
                        statement.getObject()));
            }
        }
        model.remove(toRemove);
        model.add(toAdd);
    }

    /**
     * The OFN predicate a stored predicate maps to: the same IRI when it is already OFN or unrelated,
     * an empty string when it is internal and has no OFN term, or the OFN IRI to move it to.
     */
    public static String ofnPredicateFor(String predicate) {
        String localName = OfnIris.localName(predicate);
        if (OfnIris.DCT_CONFORMS_TO.equals(predicate)) {
            return OfnIris.DEFINUJICI_USTANOVENI;
        }
        if (OfnIris.LEGACY_USTANOVENI_LOCAL_NAME.equals(localName)) {
            return OfnIris.USTANOVENI_NEVEREJNOST;
        }
        if (OfnIris.localName(OfnIris.DEFINUJICI_NELEGISLATIVNI_ZDROJ).equals(localName)) {
            return OfnIris.DEFINUJICI_NELEGISLATIVNI_ZDROJ;
        }
        if (OfnIris.localName(OfnIris.SOUVISEJICI_NELEGISLATIVNI_ZDROJ).equals(localName)) {
            return OfnIris.SOUVISEJICI_NELEGISLATIVNI_ZDROJ;
        }
        if (OfnIris.LEGACY_NADRAZENA_TRIDA_LOCAL_NAME.equals(localName)) {
            return RDFS.subClassOf.getURI();
        }
        if (OfnIris.LEGACY_IN_TEZAURUS_LOCAL_NAME.equals(localName)) {
            return "";
        }
        return predicate;
    }

    /** {@code schema:url} as an {@code xsd:anyURI} literal, whatever it was stored as. */
    private static void normalizeUrls(Model model) {
        Property url = model.createProperty(OfnIris.SCHEMA_URL);
        for (Statement statement : model.listStatements(null, url, (RDFNode) null).toList()) {
            RDFNode object = statement.getObject();
            String text;
            if (object.isURIResource()) {
                text = object.asResource().getURI();
            } else if (object.isLiteral()
                    && !XSDDatatype.XSDanyURI.getURI().equals(object.asLiteral().getDatatypeURI())) {
                text = object.asLiteral().getLexicalForm();
            } else {
                continue;
            }
            model.remove(statement);
            statement.getSubject().addLiteral(url, model.createTypedLiteral(text.trim(), XSDDatatype.XSDanyURI));
        }
    }

    /** A PPDF flag stored as {@code "true"}/{@code "false"} text becomes {@code xsd:boolean}. */
    private static void normalizePpdf(Model model) {
        Property ppdf = model.createProperty(OfnIris.JE_SDILEN_V_PPDF);
        for (Statement statement : model.listStatements(null, ppdf, (RDFNode) null).toList()) {
            if (!statement.getObject().isLiteral()) {
                continue;
            }
            Literal literal = statement.getObject().asLiteral();
            if (XSDDatatype.XSDboolean.getURI().equals(literal.getDatatypeURI())) {
                continue;
            }
            String text = literal.getLexicalForm().trim().toLowerCase(Locale.ROOT);
            model.remove(statement);
            if (text.equals("true") || text.equals("false")) {
                statement.getSubject().addLiteral(ppdf, model.createTypedLiteral(Boolean.parseBoolean(text)));
            }
        }
    }

    /** Fills {@code skos:prefLabel} from {@code rdfs:label} for languages that have none. */
    private static void copyLabels(Model model) {
        for (Statement label : model.listStatements(null, RDFS.label, (RDFNode) null).toList()) {
            if (!label.getObject().isLiteral() || label.getSubject().isAnon()) {
                continue;
            }
            String language = label.getLanguage();
            boolean hasPrefLabel = label.getSubject().listProperties(SKOS.prefLabel).toList().stream()
                    .anyMatch(existing -> existing.getObject().isLiteral()
                            && Objects.equals(existing.getLanguage(), language));
            if (!hasPrefLabel) {
                label.getSubject().addProperty(SKOS.prefLabel, label.getObject());
            }
        }
    }
}
