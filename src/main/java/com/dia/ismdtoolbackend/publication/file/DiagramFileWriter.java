package com.dia.ismdtoolbackend.publication.file;

import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Diagram;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Edge;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Node;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.OutputStream;

import static com.dia.ismdtoolbackend.publication.file.PublicationJson.*;

/**
 * Writes {@code diagramy.jsonld}: one graph node per diagram with its node positions and grouping and the
 * edges placed on it (route waypoints are not published), then the publication
 * record. Reads PostgreSQL data only; concepts are referenced by IRI and described in
 * {@code slovniky.jsonld}.
 */
@Component
public class DiagramFileWriter {

    public static final String FILE = "diagramy.jsonld";

    public void write(PublicationSnapshot snapshot, PublicationHeader header, OutputStream out) {
        JsonGenerator generator = open(out, header);
        for (Diagram diagram : snapshot.diagrams()) {
            generator.writeTree(diagramNode(diagram, header.iriBase()));
        }
        close(generator, header, FILE, true);
    }

    static ObjectNode diagramNode(Diagram diagram, String iriBase) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(IRI, iriBase + "diagramy/" + diagram.iriId());
        node.put(TYP, DIAGRAM_TYPE);
        node.put(NAZEV_DIAGRAMU, diagram.name());
        node.put(SLOVNIK, diagram.ontologyIri());
        putIfPresent(node, VYTVORENO_V_ISMD, instant(diagram.createdAt()));
        putIfPresent(node, AKTUALIZOVANO_V_ISMD, instant(diagram.updatedAt()));
        if (diagram.viewportX() != null && diagram.viewportY() != null && diagram.viewportZoom() != null) {
            ObjectNode viewport = node.putObject(VYREZ);
            viewport.put(X, diagram.viewportX());
            viewport.put(Y, diagram.viewportY());
            viewport.put(PRIBLIZENI, diagram.viewportZoom());
        }
        ArrayNode nodes = node.putArray(UZLY);
        diagram.nodes().forEach(n -> nodes.add(nodeNode(n)));
        ArrayNode edges = node.putArray(HRANY);
        diagram.edges().forEach(e -> edges.add(edgeNode(e)));
        return node;
    }

    private static ObjectNode nodeNode(Node n) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(TYP, NODE_TYPE);
        node.put(POJEM, n.conceptIri());
        node.set(POZICE, point(n.x(), n.y()));
        if (n.parentConceptIri() != null) {
            node.put(SESKUPEN_POD, n.parentConceptIri());
        }
        node.put(CIZI, n.foreign());
        return node;
    }

    private static ObjectNode edgeNode(Edge e) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(TYP, EDGE_TYPE);
        node.put(KLIC_HRANY, e.edgeKey());
        return node;
    }

    private static ObjectNode point(double x, double y) {
        ObjectNode point = MAPPER.createObjectNode();
        point.put(TYP, POINT_TYPE);
        point.put(X, x);
        point.put(Y, y);
        return point;
    }
}
