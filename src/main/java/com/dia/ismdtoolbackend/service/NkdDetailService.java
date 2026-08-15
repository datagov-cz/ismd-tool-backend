package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;

import java.util.List;

public interface NkdDetailService {

    GetNkdOntologyDto getOntologyDetail(String iri);

    GetNkdConceptDto getConceptDetail(String iri, String ontologyIri);

    GetNkdOntologyListDto getOntologyList(List<String> iris);

    GetNkdOntologyListDto listAllOntologies(int limit, int offset, String lang);

    /**
     * The concepts of one NKD ontology as slim projections (IRI, name, role) via a single targeted
     * SELECT — no full-ontology CONSTRUCT and no OFN transform, since none of the dropped fields
     * reach this response.
     *
     * <p>Returns an empty list when the ontology has no concepts or is absent from NKD.
     */
    List<MinimalConceptDto> listOntologyConcepts(String ontologyIri);

    /**
     * Serialize an NKD-published ontology for download. Returns the raw model
     * (no OFN re-formatting — NKD already publishes in OFN-aligned shape).
     *
     * @param iri    NKD ontology IRI
     * @param format {@code "ttl"} or {@code "json-ld"}; case-insensitive
     * @return serialized bytes (UTF-8)
     */
    byte[] downloadOntology(String iri, String format);
}
