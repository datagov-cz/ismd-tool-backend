package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;

import java.util.List;

public interface NkdDetailService {

    GetNkdOntologyDto getOntologyDetail(String iri);

    GetNkdConceptDto getConceptDetail(String iri, String ontologyIri);

    GetNkdOntologyListDto getOntologyList(List<String> iris);
}
