package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.NodeOverlayDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the diagram layer's pure translation: node-id ↔ IRI, concept-type → ReactFlow node-type,
 * the wire overlay → persisted-model conversion (including the all-null discard and the empty-list "clear
 * this predicate" distinction), and the live-content ⊕ overlay merge into render-ready {@code NodeData}.
 */
class DiagramMapperTest {

    private final DiagramMapper mapper = new DiagramMapper();

    @Test
    void nodeId_roundTripsWithIri() {
        String iri = "https://x/pojem/zamestnanec";
        String nodeId = mapper.nodeId(iri);

        assertThat(nodeId).isEqualTo("iri:" + iri);
        assertThat(mapper.conceptIriFromNodeId(nodeId)).isEqualTo(iri);
    }

    @Test
    void conceptIriFromNodeId_returnsInputWhenUnprefixed() {
        assertThat(mapper.conceptIriFromNodeId("https://x/pojem/a")).isEqualTo("https://x/pojem/a");
    }

    @Test
    void nodeType_mapsEachConceptType() {
        assertThat(mapper.nodeType(ConceptType.TRIDA)).isEqualTo("classNode");
        assertThat(mapper.nodeType(ConceptType.VLASTNOST)).isEqualTo("propertyNode");
        assertThat(mapper.nodeType(ConceptType.VZTAH)).isEqualTo("relationNode");
        assertThat(mapper.nodeType(ConceptType.KONCEPT)).isEqualTo("conceptNode");
        assertThat(mapper.nodeType(null)).isEqualTo("conceptNode");   // stale node fallback
    }

    // ---- overlay conversion: discard vs. clear-predicate --------------------------------------

    @Test
    void toPendingEdit_allNullBody_discards() {
        // conceptIri is addressing, not content — a body carrying only nodeId is still a discard.
        NodeOverlayDto allNull = new NodeOverlayDto("iri:https://x/pojem/n", null, null, null, null, null);
        assertThat(mapper.toPendingEdit(allNull)).isNull();   // discard
        assertThat(mapper.toPendingEdit(null)).isNull();
    }

    @Test
    void toPendingEdit_emptyListBody_stagesAsClearPredicate() {
        // op 2 flip A-side: "remove all superclasses" — an explicitly-empty list is NOT a discard.
        NodeOverlayDto clearBroader =
                new NodeOverlayDto("iri:https://x/pojem/n", null, null, List.of(), null, null);

        DiagramPendingEdit edit = mapper.toPendingEdit(clearBroader);

        assertThat(edit).isNotNull();
        assertThat(edit.getBroaderConcept()).isEmpty();       // staged clear, not discarded
    }

    @Test
    void toPendingEdit_copiesAllFieldsIncludingConvertMarker() {
        NodeOverlayDto dto = new NodeOverlayDto(
                "iri:https://x/pojem/n",
                "https://x/pojem/domain",
                "https://x/pojem/range",
                List.of("https://x/pojem/super-c"),
                List.of("https://x/pojem/match"),
                new NodeOverlayDto.ConvertToHierarchy("https://x/pojem/target", "https://x/pojem/broader"));

        DiagramPendingEdit edit = mapper.toPendingEdit(dto);

        assertThat(edit.getDomain()).isEqualTo("https://x/pojem/domain");
        assertThat(edit.getRange()).isEqualTo("https://x/pojem/range");
        assertThat(edit.getBroaderConcept()).containsExactly("https://x/pojem/super-c");
        assertThat(edit.getExactMatch()).containsExactly("https://x/pojem/match");
        assertThat(edit.getConvertToHierarchy().getAddBroaderOn()).isEqualTo("https://x/pojem/target");
        assertThat(edit.getConvertToHierarchy().getBroader()).isEqualTo("https://x/pojem/broader");
    }

    // ---- node-data merge -----------------------------------------------------------------------

    @Test
    void toNodeData_mergesOverlayAndFlagsPending() {
        DiagramNodeEntity node = new DiagramNodeEntity();
        node.setConceptIri("https://x/pojem/je-zamestnan-u");
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setRange("https://x/pojem/organizace");
        node.setPendingEdit(overlay);

        ConceptDetailModel detail = ConceptDetailModel.builder()
                .iri("https://x/pojem/je-zamestnan-u")
                .name(Map.of("cs", "je zaměstnán u"))
                .build();

        DiagramDto.NodeData data = mapper.toNodeData(
                node, ConceptType.VZTAH, "slug-je-zamestnan-u", detail.getName(), detail, List.of());

        assertThat(data.conceptType()).isEqualTo(ConceptType.VZTAH);
        assertThat(data.iri()).isEqualTo("https://x/pojem/je-zamestnan-u");
        assertThat(data.slug()).isEqualTo("slug-je-zamestnan-u");
        assertThat(data.label()).containsEntry("cs", "je zaměstnán u");
        assertThat(data.stale()).isFalse();
        assertThat(data.hasPendingEdits()).isTrue();
        assertThat(data.pendingEdit().getRange()).isEqualTo("https://x/pojem/organizace");
    }

    @Test
    void toNodeData_nullDetailMarksNodeStale() {
        DiagramNodeEntity node = new DiagramNodeEntity();
        node.setConceptIri("https://x/pojem/deleted");

        DiagramDto.NodeData data = mapper.toNodeData(node, null, null, null, null, null);

        assertThat(data.stale()).isTrue();          // concept deleted underneath the node
        assertThat(data.hasPendingEdits()).isFalse();
        assertThat(data.pendingEdit()).isNull();
        assertThat(data.properties()).isEmpty();    // always a list on the wire, never null
    }
}
