package com.dia.ismdtoolbackend.publication.file;

import com.dia.ismdtoolbackend.constants.VocabularyConstants;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Keys, value shapes and the shared document frame of both published files. */
final class PublicationJson {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** PostgreSQL stores local timestamps; publication states the zone explicitly. */
    static final ZoneId ZONE = ZoneId.of("Europe/Prague");

    // JSON-LD keywords and OFN terms
    static final String CONTEXT = "@context";
    static final String GRAPH = "@graph";
    static final String IRI = "iri";
    static final String TYP = "typ";
    static final String POJMY = VocabularyConstants.JSON_POJMY;
    static final String SLOVNIK_TYPE = "Slovník";
    static final String TIME_TYPE = "Časový okamžik";
    static final String DATUM_A_CAS = "datum_a_čas";

    // ISMD OD terms; spelling follows DCAT-AP-CZ (underscores)
    static final String RECORD_TYPE = "Publikace ISMD";
    static final String PUBLIKOVANO = "publikováno";
    static final String UPLNA = "úplná";
    static final String OBSAHUJE_ROZPRACOVANE = "obsahuje_rozpracované";
    static final String SLUG = "slug";
    static final String JE_PUBLIKOVAN = "je_publikován";
    static final String STAV_POSLEDNI_VALIDACE = "stav_poslední_validace";
    static final String OKAMZIK_POSLEDNI_VALIDACE = "okamžik_poslední_validace";
    static final String STAV_OBSAHU = "stav_obsahu";
    static final String POCET_POJMU = "počet_pojmů";
    static final String VYTVORENO_V_ISMD = "vytvořeno_v_ismd";
    static final String AKTUALIZOVANO_V_ISMD = "aktualizováno_v_ismd";
    static final String DIAGRAM_TYPE = "Diagram";
    static final String NODE_TYPE = "Uzel diagramu";
    static final String EDGE_TYPE = "Hrana diagramu";
    static final String POINT_TYPE = "Bod";
    static final String NAZEV_DIAGRAMU = "název_diagramu";
    static final String SLOVNIK = "slovník";
    static final String VYREZ = "výřez";
    static final String PRIBLIZENI = "přiblížení";
    static final String X = "x";
    static final String Y = "y";
    static final String UZLY = "uzly";
    static final String POJEM = "pojem";
    static final String POZICE = "pozice";
    static final String SESKUPEN_POD = "seskupen_pod";
    static final String CIZI = "cizí";
    static final String HRANY = "hrany";
    static final String KLIC_HRANY = "klíč_hrany";

    private PublicationJson() {
    }

    /** Opens {@code {"@context": [OFN, ISMD OD], "@graph": [}; the caller writes the nodes and closes. */
    static JsonGenerator open(OutputStream out, PublicationHeader header) {
        JsonGenerator generator = MAPPER.createGenerator(out);
        generator.writeStartObject();
        generator.writeArrayPropertyStart(CONTEXT);
        generator.writeString(VocabularyConstants.CONTEXT);
        generator.writeString(header.contextUrl());
        generator.writeEndArray();
        generator.writeArrayPropertyStart(GRAPH);
        return generator;
    }

    /** Writes the publication record as the last graph node and closes the document. */
    static void close(JsonGenerator generator, PublicationHeader header, String file, boolean complete) {
        ObjectNode record = MAPPER.createObjectNode();
        record.put(IRI, header.iriBase() + "publikace/" + file);
        record.put(TYP, RECORD_TYPE);
        record.set(PUBLIKOVANO, instant(header.publishedAt()));
        record.put(UPLNA, complete);
        record.put(OBSAHUJE_ROZPRACOVANE, header.includesDrafts());
        generator.writeTree(record);
        generator.writeEndArray();
        generator.writeEndObject();
        generator.flush();
    }

    /** An OFN {@code Časový okamžik}, or null for a missing value. */
    static ObjectNode instant(LocalDateTime local) {
        return local == null ? null : instant(local.atZone(ZONE).toInstant());
    }

    static ObjectNode instant(Instant instant) {
        if (instant == null) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put(TYP, TIME_TYPE);
        node.put(DATUM_A_CAS, DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(instant.truncatedTo(ChronoUnit.SECONDS).atZone(ZONE)));
        return node;
    }

    /** Sets {@code key} only when {@code value} is non-null, so absent data is absent, not null. */
    static void putIfPresent(ObjectNode node, String key, ObjectNode value) {
        if (value != null) {
            node.set(key, value);
        }
    }
}
