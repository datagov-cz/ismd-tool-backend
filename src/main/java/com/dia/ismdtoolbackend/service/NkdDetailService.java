package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;

import java.util.List;

public interface NkdDetailService {

    GetNkdOntologyDto getOntologyDetail(String iri);

    GetNkdConceptDto getConceptDetail(String iri, String ontologyIri);

    GetNkdOntologyListDto getOntologyList(List<String> iris);

    GetNkdOntologyListDto listAllOntologies(int limit, int offset, String lang);

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
