package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.jena.rdf.model.Model;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The ISMD vocabulary download. The stored graph is read into one OFN document; JSON-LD is that document
 * and Turtle is that document parsed back, so both formats are the same graph. A vocabulary with any
 * issue (shape, concept kind or completeness) is not downloaded: the user gets every issue to correct.
 * Nothing is repaired or left out.
 */
@Component
public class OfnVocabularyExporter {

    /** A download format, by the name the API accepts. */
    public enum Format {
        JSON_LD("json-ld"), TURTLE("ttl");

        private final String apiName;

        Format(String apiName) {
            this.apiName = apiName;
        }

        /** @throws IllegalArgumentException for an unsupported or null name */
        public static Format of(String name) {
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            for (Format format : values()) {
                if (format.apiName.equals(normalized)) {
                    return format;
                }
            }
            throw new IllegalArgumentException("Nepodporovaný formát: " + name);
        }
    }

    private final OfnWriteGate gate;
    private final OfnDocumentOrder order;
    private final OfnRdf rdf;

    public OfnVocabularyExporter(OfnWriteGate gate, OfnSpecification specification, OfnRdf rdf) {
        this.gate = gate;
        this.order = new OfnDocumentOrder(specification.contextIndex());
        this.rdf = rdf;
    }

    /** @throws OfnConformanceException (download context) when the vocabulary has any issue */
    public String export(Model graph, String vocabularyIri, Format format) {
        OfnWriteGate.Inspection inspection = gate.inspectVocabulary(graph, vocabularyIri);
        if (!inspection.issues().isEmpty()) {
            throw new OfnConformanceException(OfnConformanceException.Context.DOWNLOAD, vocabularyIri, inspection.issues());
        }
        ObjectNode document = order.apply(inspection.document());
        return format == Format.JSON_LD ? rdf.toJsonLd(document) : rdf.toTurtle(document);
    }
}
