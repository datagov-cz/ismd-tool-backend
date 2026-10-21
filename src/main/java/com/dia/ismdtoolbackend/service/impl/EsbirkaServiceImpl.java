package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Entry point for e-Sbírka reads. Delegates each operation to its focused class, where the cache of
 * that operation lives.
 */
@Service
@RequiredArgsConstructor
public class EsbirkaServiceImpl implements EsbirkaService {

    private final EsbirkaLawSearch lawSearch;
    private final EsbirkaLawVersionsReader versionsReader;
    private final EsbirkaLawContentReader contentReader;
    private final EsbirkaLegalSourceResolver legalSourceResolver;

    @Override
    public List<LawDto> searchLaws(String q, int limit) {
        return lawSearch.searchLaws(q, limit);
    }

    @Override
    public LawSearchResultDto searchLawsGrouped(String q, int limit) {
        return lawSearch.searchLawsGrouped(q, limit);
    }

    @Override
    public List<LawVersionDto> getVersions(String lawIri) {
        return versionsReader.getVersions(lawIri);
    }

    @Override
    public List<FragmentDto> getFragments(String versionIri) {
        return contentReader.getFragments(versionIri);
    }

    /**
     * Resolve a "number/year" law reference (e.g. "49/1997") to the full rendered content of
     * its latest version: parse the ref → exact law lookup → latest znění → content query.
     * Shares the cache entry of the two-argument overload.
     */
    @Override
    public LawContentDto getLawContent(String lawRef) {
        return contentReader.getLawContent(lawRef, null);
    }

    @Override
    public LawContentDto getLawContent(String lawRef, String versionIri) {
        return contentReader.getLawContent(lawRef, versionIri);
    }

    @Override
    public ResolvedLegalSourceDto resolveLegalSource(String url) {
        return legalSourceResolver.resolveLegalSource(url);
    }
}
