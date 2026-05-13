package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;

import java.util.List;

public interface EsbirkaService {

    List<LawDto> searchLaws(String q, int limit);

    List<LawVersionDto> getVersions(String lawIri);

    List<FragmentDto> getFragments(String versionIri);

    ResolvedLegalSourceDto resolveLegalSource(String url);
}
