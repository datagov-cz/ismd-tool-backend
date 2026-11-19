package com.dia.ismdtoolbackend.ofn;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Writes OFN časový okamžik values: a blank node typed {@code čas:časový-okamžik} whose
 * {@code čas:datum-a-čas} is an {@code xsd:dateTimeStamp} (always with a zone offset).
 */
public final class OfnDates {

    /** The zone ISMD timestamps are written in. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Prague");

    private OfnDates() {
    }

    /** The current time in {@link #ZONE}, to whole seconds. */
    public static OffsetDateTime now(Clock clock) {
        return OffsetDateTime.now(clock.withZone(ZONE)).truncatedTo(ChronoUnit.SECONDS);
    }

    /** A new časový okamžik blank node for {@code at} in {@code model}. */
    public static Resource instant(Model model, OffsetDateTime at) {
        Resource instant = model.createResource();
        instant.addProperty(RDF.type, model.createResource(OfnIris.CASOVY_OKAMZIK));
        instant.addLiteral(model.createProperty(OfnIris.DATUM_A_CAS),
                model.createTypedLiteral(at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        XSDDatatype.XSDdateTimeStamp));
        return instant;
    }

    /**
     * A zone-less stored timestamp in {@link #ZONE}, to whole seconds. It is read in the JVM's zone, the
     * zone it was written in; null stays null.
     */
    public static OffsetDateTime fromStored(LocalDateTime stored) {
        if (stored == null) {
            return null;
        }
        return stored.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZONE)
                .toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
    }

    /** {@link #fromStored} as the {@code datum-a-čas} lexical form; null stays null. */
    public static String formatStored(LocalDateTime stored) {
        OffsetDateTime at = fromStored(stored);
        return at == null ? null : at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Sets the vocabulary's {@code okamžik-poslední-změny} to {@code lastChange} (see {@link #fromStored}),
     * replacing any stored value with its instant node; with a null {@code lastChange} the vocabulary has none.
     */
    public static void replaceLastChange(Model model, String vocabularyIri, LocalDateTime lastChange) {
        Resource vocabulary = model.createResource(vocabularyIri);
        Property lastChangeProperty = model.createProperty(OfnIris.OKAMZIK_POSLEDNI_ZMENY);
        for (RDFNode old : model.listObjectsOfProperty(vocabulary, lastChangeProperty).toList()) {
            if (old.isAnon()) {
                model.removeAll(old.asResource(), null, null);
            }
        }
        vocabulary.removeAll(lastChangeProperty);
        if (lastChange != null) {
            vocabulary.addProperty(lastChangeProperty, instant(model, fromStored(lastChange)));
        }
    }
}
