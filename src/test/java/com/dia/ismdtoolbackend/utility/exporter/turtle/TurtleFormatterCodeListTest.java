package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The číselník must survive TTL export as a standalone subject, as the OFN documentation
 * shows it. {@code TurtleFilterUtil} drops named subjects whose every {@code rdf:type} is a
 * vocabulary term — {@code l111-2009:číselník} is deliberately not one of those, so the
 * subject and its dataset triple stay in the output.
 */
@DisplayName("TurtleFormatterUtil - Code list (číselník) standalone subject")
class TurtleFormatterCodeListTest {

    private static final String CODE_LIST_IRI =
            "https://data.mvcr.gov.cz/zdroj/číselníky/typy-turistických-cílů";
    private static final String NKOD_DATASET =
            "https://data.gov.cz/zdroj/datové-sady/17651921/ff931872553062c9890157ce8615af03";

    private OntModel modelWithCodeList() {
        OntModel model = createDefaultModel();

        Resource cls = model.createResource(TEST_POJEM_NS + "typ-turistického-cíle");
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDFS.label, model.createLiteral("Typ turistického cíle", "cs"));

        Resource codeList = model.createResource(CODE_LIST_IRI);
        codeList.addProperty(RDF.type, model.createResource(OFN_NAMESPACE_LEGAL + CISELNIK));
        codeList.addProperty(
                model.createProperty(OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU),
                model.createResource(NKOD_DATASET));

        cls.addProperty(
                model.createProperty(OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM),
                codeList);

        return model;
    }

    @Test
    @DisplayName("Filtering keeps the číselník subject and its dataset triple")
    void filterKeepsCodeListSubject() {
        Model filtered = TurtleFilterUtil.createFilteredModel(modelWithCodeList());

        Resource codeList = filtered.getResource(CODE_LIST_IRI);
        Property datasetProp = filtered.createProperty(
                OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);

        assertTrue(filtered.containsResource(codeList),
                "číselník must survive filtering as its own subject");
        assertTrue(codeList.hasProperty(RDF.type,
                        filtered.getResource(OFN_NAMESPACE_LEGAL + CISELNIK)),
                "číselník must keep its rdf:type");
        assertTrue(codeList.hasProperty(datasetProp),
                "číselník must keep its NKOD dataset triple");
    }

    @Test
    @DisplayName("Exported Turtle contains the číselník as a standalone subject")
    void exportedTurtleContainsStandaloneSubject() {
        Model filtered = TurtleFilterUtil.createFilteredModel(modelWithCodeList());
        Model transformed = TurtleFormatterUtil.transformToOFNFormat(filtered);

        StringWriter out = new StringWriter();
        RDFDataMgr.write(out, transformed, Lang.TURTLE);
        String ttl = out.toString();

        assertTrue(ttl.contains(CODE_LIST_IRI),
                "serialized Turtle must mention the číselník IRI:\n" + ttl);
        assertTrue(ttl.contains(NKOD_DATASET),
                "serialized Turtle must mention the NKOD dataset IRI:\n" + ttl);

        // Re-parse: the číselník must still be a subject carrying both triples, not a
        // bare object reference dangling off the concept.
        Model reparsed = ModelFactory.createDefaultModel();
        RDFDataMgr.read(reparsed, new StringReader(ttl), null, Lang.TURTLE);

        Resource codeList = reparsed.getResource(CODE_LIST_IRI);
        assertTrue(codeList.hasProperty(RDF.type,
                        reparsed.getResource(OFN_NAMESPACE_LEGAL + CISELNIK)),
                "re-parsed číselník must keep its type");
        assertEquals(NKOD_DATASET,
                codeList.getProperty(reparsed.createProperty(
                                OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU))
                        .getObject().asResource().getURI(),
                "re-parsed číselník must keep its dataset");
    }
}
