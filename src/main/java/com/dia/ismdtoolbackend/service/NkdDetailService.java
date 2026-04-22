package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;

public interface NkdDetailService {

    GetNkdOntologyDto getOntologyDetail(String iri);

    GetNkdConceptDto getConceptDetail(String iri, String ontologyIri);
}
