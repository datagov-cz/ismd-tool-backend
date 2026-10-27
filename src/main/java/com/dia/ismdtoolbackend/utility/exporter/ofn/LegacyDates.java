package com.dia.ismdtoolbackend.utility.exporter.ofn;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Vocabulary creation and change dates in the OFN shape: a {@code čas:časový-okamžik} blank node with
 * {@code čas:datum-a-čas} as a zoned {@code xsd:dateTimeStamp}, or {@code čas:datum} as {@code xsd:date}.
 * Reads the legacy shapes (a named node or a flat literal; a value without a time zone, which is
 * taken as Europe/Prague time).
 */
public final class LegacyDates {

    /** The zone of values the tool stored without one. */
    public static final ZoneId STORAGE_ZONE = ZoneId.of("Europe/Prague");

    private LegacyDates() {
    }

    static void normalize(Model model) {
        for (String predicate : List.of(OfnIris.OKAMZIK_VYTVORENI, OfnIris.OKAMZIK_POSLEDNI_ZMENY)) {
            Property property = model.createProperty(predicate);
            for (Statement statement : model.listStatements(null, property, (RDFNode) null).toList()) {
                normalizeOne(model, statement);
            }
        }
    }

    private static void normalizeOne(Model model, Statement statement) {
        RDFNode object = statement.getObject();
        Literal value = object.isLiteral() ? object.asLiteral() : valueOf(object.asResource());
        if (object.isResource() && isOfnShape(object.asResource(), value)) {
            return;
        }
        model.remove(statement);
        if (object.isResource()) {
            object.asResource().removeProperties();
        }
        if (value != null) {
            toInstant(value.getLexicalForm()).ifPresent(instant ->
                    statement.getSubject().addProperty(statement.getPredicate(), instantNode(model, instant)));
        }
    }

    private static boolean isOfnShape(Resource node, Literal value) {
        if (!node.isAnon() || value == null) {
            return false;
        }
        String datatype = value.getDatatypeURI();
        return XSDDatatype.XSDdateTimeStamp.getURI().equals(datatype) || XSDDatatype.XSDdate.getURI().equals(datatype);
    }

    private static Literal valueOf(Resource node) {
        for (String predicate : List.of(OfnIris.DATUM_A_CAS, OfnIris.DATUM)) {
            Statement value = node.getProperty(node.getModel().createProperty(predicate));
            if (value != null && value.getObject().isLiteral()) {
                return value.getObject().asLiteral();
            }
        }
        return null;
    }

    /** A blank-node Časový okamžik holding the value. */
    public static Resource instantNode(Model model, Literal value) {
        Resource node = model.createResource();
        node.addProperty(RDF.type, model.createResource(OfnIris.CASOVY_OKAMZIK));
        boolean dateOnly = XSDDatatype.XSDdate.getURI().equals(value.getDatatypeURI());
        node.addLiteral(model.createProperty(dateOnly ? OfnIris.DATUM : OfnIris.DATUM_A_CAS), value);
        return node;
    }

    /** {@code xsd:dateTimeStamp} literal for a moment. */
    public static Literal dateTimeStamp(Model model, OffsetDateTime moment) {
        return model.createTypedLiteral(moment.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                XSDDatatype.XSDdateTimeStamp);
    }

    /** The lexical form as an {@code xsd:dateTimeStamp} (zone added when missing) or {@code xsd:date}. */
    static Optional<Literal> toInstant(String lexical) {
        String text = lexical.trim();
        Model factory = ModelFactory.createDefaultModel();
        try {
            return Optional.of(dateTimeStamp(factory, OffsetDateTime.parse(text)));
        } catch (DateTimeParseException ignored) {
            // not zoned
        }
        try {
            return Optional.of(dateTimeStamp(factory, LocalDateTime.parse(text).atZone(STORAGE_ZONE).toOffsetDateTime()));
        } catch (DateTimeParseException ignored) {
            // not a date-time
        }
        try {
            LocalDate date = LocalDate.parse(text);
            return Optional.of(factory.createTypedLiteral(date.toString(), XSDDatatype.XSDdate));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
