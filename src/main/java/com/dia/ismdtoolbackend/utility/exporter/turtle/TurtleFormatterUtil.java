package com.dia.ismdtoolbackend.utility.exporter.turtle;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dia.ismdtoolbackend.exception.TurtleExportException;

import static com.dia.constants.VocabularyConstants.*;

@Slf4j
public class TurtleFormatterUtil {

    private static final String NADRAZENA_TRIDA = "https://slovník.gov.cz/nadřazená-třída";
    private static final String AGENDOVY_POJEM = "https://slovník.gov.cz/agendový/104/pojem/";
    private static final String SCHEMA = "http://schema.org/";
    private static final String TYP_OBSAHU_UDAJU = "https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-typ-obsahu-údaje";
    private static final String ZPUSOB_SDILENI_UDAJU = "https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-způsob-sdílení-údaje";
    private static final String ZPUSOB_ZISKANI_UDAJU = "https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-způsob-získání-údaje";

    private static final Map<String, String> OFN_PREFIXES = new HashMap<>();

    static {
        OFN_PREFIXES.put("dct", DCT_NS);
        OFN_PREFIXES.put("owl", OWL2.getURI());
        OFN_PREFIXES.put("rdf", RDF.getURI());
        OFN_PREFIXES.put("rdfs", RDFS.getURI());
        OFN_PREFIXES.put("skos", SKOS_NS);
        OFN_PREFIXES.put("slovníky", OFN_NAMESPACE);
        OFN_PREFIXES.put("vsgov", OFN_NAMESPACE_VS);
        OFN_PREFIXES.put("xsd", XSD);
        OFN_PREFIXES.put("čas", CAS_NS);
        OFN_PREFIXES.put("a104", AGENDOVY_POJEM);
        OFN_PREFIXES.put("l111-2009", OFN_NAMESPACE_LEGAL);
        OFN_PREFIXES.put("schema", SCHEMA);
        OFN_PREFIXES.put("typ-obsahu-údajů", TYP_OBSAHU_UDAJU);
        OFN_PREFIXES.put("způsoby-sdílení-údajů", ZPUSOB_SDILENI_UDAJU);
        OFN_PREFIXES.put("způsoby-získání-údajů", ZPUSOB_ZISKANI_UDAJU);
    }

    private TurtleFormatterUtil() {}

    public static Model transformToOFNFormat(Model filteredModel) {
        if (filteredModel == null) {
            throw new TurtleExportException("Filtered model cannot be null");
        }

        log.debug("Starting OFN format transformation");

        try {
            OntModel ofnModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

            setupOFNPrefixes(ofnModel);

            StmtIterator stmtIter = filteredModel.listStatements();
            while (stmtIter.hasNext()) {
                Statement stmt = stmtIter.next();
                ofnModel.add(stmt);
            }

            transformDescriptionProperties(ofnModel);
            transformConformsToProperties(ofnModel);
            transformPrivacyProvisionProperties(ofnModel);
            transformSubClassRelationships(ofnModel);
            ensureConceptSchemeFormat(ofnModel);

            log.debug("OFN format transformation completed successfully");
            return ofnModel;

        } catch (Exception e) {
            log.error("Error during OFN format transformation: {}", e.getMessage(), e);
            throw new TurtleExportException("Failed to transform to OFN format: " + e.getMessage(), e);
        }
    }

    private static void setupOFNPrefixes(OntModel model) {
        for (Map.Entry<String, String> prefix : OFN_PREFIXES.entrySet()) {
            model.setNsPrefix(prefix.getKey(), prefix.getValue());
        }
    }

    private static void transformDescriptionProperties(OntModel model) {
        Property dctermsDescript = model.getProperty(DCT_NS + "description");

        List<Statement> toUpdate = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, dctermsDescript, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            toUpdate.add(stmt);
        }

        model.setNsPrefix("dct", DCT_NS);

        log.debug("Processed {} description properties", toUpdate.size());
    }

    private static void transformConformsToProperties(OntModel model) {
        Property conformsTo = model.getProperty(DCT_NS + "conformsTo");
        Property ofnDefinujiciUstanoveni = model.getProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);

        List<Statement> toReplace = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, conformsTo, (RDFNode) null);
        while (iter.hasNext()) {
            toReplace.add(iter.next());
        }

        for (Statement stmt : toReplace) {
            model.remove(stmt);
            model.add(stmt.getSubject(), ofnDefinujiciUstanoveni, stmt.getObject());
        }
    }

    /**
     * The writers store the privacy provision under the internal property IRI
     * {@code OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST}
     * ({@code …/ustanovení-dokládající-neveřejnost-údaje}). The canonical OFN
     * property IRI, per the JSON-LD context, is
     * {@code OFN_NAMESPACE_LEGAL + USTANOVENI_LONG}
     * ({@code …/je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost}), which
     * renders as {@code l111-2009:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost}
     * in the exported Turtle. Rewrite the internal property to the canonical one
     * on export (mirrors {@link #transformConformsToProperties}).
     */
    private static void transformPrivacyProvisionProperties(OntModel model) {
        Property internal = model.getProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        Property canonical = model.getProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_LONG);

        List<Statement> toReplace = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, internal, (RDFNode) null);
        while (iter.hasNext()) {
            toReplace.add(iter.next());
        }

        for (Statement stmt : toReplace) {
            model.remove(stmt);
            model.add(stmt.getSubject(), canonical, stmt.getObject());
        }

        log.debug("Rewrote {} privacy-provision statements to canonical OFN property", toReplace.size());
    }

    private static void transformSubClassRelationships(OntModel model) {
        Property nadrazenaTrida = model.createProperty(NADRAZENA_TRIDA);

        List<Statement> subClassStatements = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, RDFS.subClassOf, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isResource() &&
                    !stmt.getObject().asResource().getURI().equals(RDFS.Resource.getURI())) {
                subClassStatements.add(stmt);
            }
        }

        for (Statement stmt : subClassStatements) {
            Resource subject = stmt.getSubject();
            Resource object = stmt.getObject().asResource();

            subject.addProperty(nadrazenaTrida, object);
        }
    }

    private static void ensureConceptSchemeFormat(OntModel model) {
        StmtIterator iter = model.listStatements(null, RDF.type, OWL2.Ontology);
        while (iter.hasNext()) {
            Resource ontology = iter.next().getSubject();

            Property skosConceptScheme = model.getProperty(SKOS_NS + "ConceptScheme");
            Property slovnikType = model.getProperty(OFN_NAMESPACE + "slovník");

            if (!ontology.hasProperty(RDF.type, skosConceptScheme)) {
                ontology.addProperty(RDF.type, skosConceptScheme);
            }
            if (!ontology.hasProperty(RDF.type, slovnikType)) {
                ontology.addProperty(RDF.type, slovnikType);
            }
        }
    }
}
