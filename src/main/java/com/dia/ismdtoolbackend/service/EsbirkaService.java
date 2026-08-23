package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;

import java.util.List;

public interface EsbirkaService {

    List<LawDto> searchLaws(String q, int limit);

    /**
     * Law search grouped by předpis number, with an explicit ambiguity signal.
     *
     * <p>Same matching as {@link #searchLaws} but bucketed: a bare "49" matches ~80 unrelated
     * acts (Czech law renumbers each year), which a flat limited list truncates into
     * uselessness. Grouping surfaces the choice instead of hiding it.
     *
     * @param limit maximum number of <em>groups</em> to return, not rows
     */
    LawSearchResultDto searchLawsGrouped(String q, int limit);

    List<LawVersionDto> getVersions(String lawIri);

    List<FragmentDto> getFragments(String versionIri);

    /**
     * Resolve a "number/year" law reference (e.g. "49/1997") to the full rendered
     * content of its latest version: header metadata, version list, and fragment tree
     * with per-node HTML bodies.
     */
    LawContentDto getLawContent(String lawRef);

    /**
     * Same as {@link #getLawContent(String)} but renders the caller-chosen znění instead
     * of the latest one. A null/blank {@code versionIri} falls back to the latest version.
     *
     * <p>The IRI must identify a version of the resolved law — it is checked against that
     * law's own version list, so a well-formed IRI belonging to a different act is rejected.
     */
    LawContentDto getLawContent(String lawRef, String versionIri);

    ResolvedLegalSourceDto resolveLegalSource(String url);
}
