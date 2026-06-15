package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;

import java.util.List;

public interface EsbirkaService {

    List<LawDto> searchLaws(String q, int limit);

    List<LawVersionDto> getVersions(String lawIri);

    List<FragmentDto> getFragments(String versionIri);

    /**
     * Resolve a "number/year" law reference (e.g. "49/1997") to the full rendered
     * content of its latest version: header metadata, version list, and fragment tree
     * with per-node HTML bodies.
     */
    LawContentDto getLawContent(String lawRef);

    ResolvedLegalSourceDto resolveLegalSource(String url);
}
