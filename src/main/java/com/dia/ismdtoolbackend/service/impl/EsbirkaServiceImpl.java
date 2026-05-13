package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EsbirkaServiceImpl implements EsbirkaService {

    static final int MAX_FRAGMENT_DEPTH = 10;
    static final int FRAGMENT_ROW_WARN_THRESHOLD = 5_000;
    private static final String NORMA_SUFFIX = "/dokument/norma";

    private static final DateTimeFormatter CZECH_DATE = DateTimeFormatter.ofPattern("d. M. yyyy");

    private final EsbirkaSparqlClient client;
    private final EsbirkaFragmentResolutionCache resolutionCache;

    @Override
    @Cacheable(cacheNames = "esbirkaLawSearch", key = "T(java.util.Objects).hash(#q, #limit)")
    public List<LawDto> searchLaws(String q, int limit) {
        List<LawModel> rows = client.searchLaws(q, limit);
        List<LawDto> out = new ArrayList<>(rows.size());
        for (LawModel m : rows) {
            out.add(toLawDto(m));
        }
        return out;
    }

    @Override
    @Cacheable(cacheNames = "esbirkaLawVersions", key = "#lawIri")
    public List<LawVersionDto> getVersions(String lawIri) {
        if (!SparqlIriValidator.isEsbirkaEliIri(lawIri)) {
            throw new IllegalArgumentException("Neplatný identifikátor právního aktu.");
        }
        List<LawVersionModel> rows = client.fetchVersions(lawIri);
        List<LawVersionDto> out = new ArrayList<>(rows.size());
        for (LawVersionModel m : rows) {
            out.add(toVersionDto(m));
        }
        return out;
    }

    @Override
    public List<FragmentDto> getFragments(String versionIri) {
        if (!SparqlIriValidator.isEsbirkaEliIri(versionIri)) {
            throw new IllegalArgumentException("Neplatný identifikátor znění právního aktu.");
        }
        List<FragmentModel> rows = client.fetchFragments(versionIri);
        if (rows.size() > FRAGMENT_ROW_WARN_THRESHOLD) {
            log.warn("Fragment tree for {} has {} rows (over {} threshold).",
                    versionIri, rows.size(), FRAGMENT_ROW_WARN_THRESHOLD);
        }
        return assembleTree(rows, versionIri);
    }

    /**
     * Tree assembly. Top-level fragments have parent = {@code <versionIri>/dokument/norma}.
     * Multi-root is supported (any number of children of the norma node).
     * Orphans (rows whose parent IRI is not in the result set and is not the norma root)
     * are dropped with a warn-log. Depth is capped at {@link #MAX_FRAGMENT_DEPTH} as a
     * defensive measure against cyclic / pathological data.
     */
    List<FragmentDto> assembleTree(List<FragmentModel> rows, String versionIri) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String normaRoot = versionIri + NORMA_SUFFIX;

        Map<String, FragmentDto> nodes = new HashMap<>(rows.size());
        for (FragmentModel m : rows) {
            nodes.put(m.getIri(), toFragmentDto(m));
        }

        List<FragmentDto> roots = new ArrayList<>();
        int orphanCount = 0;
        for (FragmentModel m : rows) {
            FragmentDto self = nodes.get(m.getIri());
            String parent = m.getParentIri();
            if (normaRoot.equals(parent)) {
                roots.add(self);
                continue;
            }
            FragmentDto parentNode = nodes.get(parent);
            if (parentNode == null) {
                orphanCount++;
                continue;
            }
            parentNode.getChildren().add(self);
        }

        if (orphanCount > 0) {
            log.warn("Dropped {} orphan fragment row(s) for version {} (parent IRI not in result set and not norma root).",
                    orphanCount, versionIri);
        }

        capDepth(roots, 1, versionIri);
        return roots;
    }

    private void capDepth(List<FragmentDto> nodes, int depth, String versionIri) {
        if (depth > MAX_FRAGMENT_DEPTH) {
            int trimmed = nodes.size();
            for (FragmentDto n : nodes) {
                n.getChildren().clear();
            }
            log.warn("Fragment tree depth cap reached for version {} at depth {} ({} subtree(s) trimmed).",
                    versionIri, MAX_FRAGMENT_DEPTH, trimmed);
            return;
        }
        for (FragmentDto n : nodes) {
            if (!n.getChildren().isEmpty()) {
                capDepth(n.getChildren(), depth + 1, versionIri);
            }
        }
    }

    private static LawDto toLawDto(LawModel m) {
        String eliPath = SparqlIriValidator.extractEsbirkaEliPath(m.getIri());
        return new LawDto(
                m.getIri(),
                eliPath,
                SparqlIriValidator.esbirkaDomain(),
                m.getCitace(),
                "Zákon č. " + m.getCitace(),
                m.getCislo(),
                m.getRok(),
                m.getSbirka()
        );
    }

    private static LawVersionDto toVersionDto(LawVersionModel m) {
        String eliPath = SparqlIriValidator.extractEsbirkaEliPath(m.getIri());
        return new LawVersionDto(
                m.getIri(),
                eliPath,
                m.getUcinnostOd(),
                m.getUcinnostDo(),
                m.getVersionType(),
                m.isLatest()
        );
    }

    private static FragmentDto toFragmentDto(FragmentModel m) {
        FragmentDto dto = new FragmentDto();
        dto.setIri(m.getIri());
        dto.setEliPath(SparqlIriValidator.extractEsbirkaEliPath(m.getIri()));
        dto.setKind(m.getKind());
        dto.setCitation(m.getCitation());
        dto.setOrder(m.getOrder());
        return dto;
    }

    @Override
    public ResolvedLegalSourceDto resolveLegalSource(String url) {
        ParsedEli parsed = EsbirkaEliParser.parse(url);
        if (!parsed.isValid()) {
            return ResolvedLegalSourceDto.builder()
                    .originalUrl(url)
                    .enrichmentStatus(EnrichmentStatus.INVALID_IRI)
                    .build();
        }
        if (!parsed.isFragment()) {
            return baseDtoBuilder(parsed)
                    .displayLabel(buildDisplayLabel(parsed, null))
                    .enrichmentStatus(EnrichmentStatus.SKIPPED_NON_FRAGMENT)
                    .build();
        }
        return enrichFragment(parsed);
    }

    private ResolvedLegalSourceDto enrichFragment(ParsedEli parsed) {
        try {
            Optional<FragmentResolutionModel> opt = resolutionCache.fetch(
                    parsed.fragmentIri(), parsed.versionIri(), parsed.lawIri());
            if (opt.isEmpty()) {
                return baseDtoBuilder(parsed)
                        .displayLabel(buildDisplayLabel(parsed, null))
                        .enrichmentStatus(EnrichmentStatus.NOT_FOUND)
                        .build();
            }
            FragmentResolutionModel m = opt.get();
            return baseDtoBuilder(parsed)
                    .fragmentCitation(m.citation())
                    .versionValidUntil(m.versionValidUntil())
                    .isLatestVersion(m.isLatest())
                    .displayLabel(buildDisplayLabel(parsed, m.citation()))
                    .enrichmentStatus(EnrichmentStatus.OK)
                    .build();
        } catch (SparqlEndpointUnavailableException e) {
            log.debug("e-Sbírka unavailable while resolving {}: {}", parsed.fragmentIri(), e.getMessage());
            return baseDtoBuilder(parsed)
                    .displayLabel(buildDisplayLabel(parsed, null))
                    .enrichmentStatus(EnrichmentStatus.UNAVAILABLE)
                    .build();
        }
    }

    private static ResolvedLegalSourceDto.ResolvedLegalSourceDtoBuilder baseDtoBuilder(ParsedEli p) {
        return ResolvedLegalSourceDto.builder()
                .originalUrl(p.originalUrl())
                .domain(p.domain())
                .eliPath(p.eliPath())
                .level(p.level())
                .lawIri(p.lawIri())
                .versionIri(p.versionIri())
                .fragmentIri(p.fragmentIri())
                .lawNumber(p.lawNumber())
                .lawYear(p.lawYear())
                .sbirkaCode(p.sbirkaCode())
                .versionDate(p.versionDate())
                .fragmentSegments(p.fragmentSegments());
    }

    /**
     * Build a display label from the parsed components, optionally using
     * an authoritative SPARQL-fetched citation for the fragment portion.
     */
    static String buildDisplayLabel(ParsedEli p, String sparqlCitation) {
        StringBuilder sb = new StringBuilder();
        sb.append("Zákon č. ").append(p.lawNumber()).append("/").append(p.lawYear()).append(" Sb.");
        if (p.isFragment()) {
            String fragmentPart = sparqlCitation != null && !sparqlCitation.isBlank()
                    ? sparqlCitation
                    : buildFragmentCitationFromSegments(p.fragmentSegments());
            if (!fragmentPart.isBlank()) {
                sb.append(", ").append(fragmentPart);
            }
        }
        if (p.versionDate() != null) {
            sb.append(" (znění od ").append(p.versionDate().format(CZECH_DATE)).append(")");
        }
        return sb.toString();
    }

    /**
     * Fallback fragment citation built purely from path segments. Used when
     * SPARQL is unavailable, the fragment isn't in the dataset, or we skip
     * SPARQL on principle. If a {@code par_} segment is present, structural
     * ancestors (cast/hlava/dil/oddil) are omitted — that matches e-Sbírka's
     * own citation format.
     */
    static String buildFragmentCitationFromSegments(List<ParsedEli.FragmentSegment> segments) {
        if (segments == null || segments.isEmpty()) return "";
        boolean hasPar = segments.stream().anyMatch(s -> "par".equals(s.kind()));
        StringBuilder sb = new StringBuilder();
        for (ParsedEli.FragmentSegment s : segments) {
            if (hasPar && isStructuralAncestor(s.kind())) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(formatSegment(s));
        }
        return sb.toString();
    }

    private static boolean isStructuralAncestor(String kind) {
        return "cast".equals(kind) || "hlava".equals(kind) || "dil".equals(kind) || "oddil".equals(kind);
    }

    private static String formatSegment(ParsedEli.FragmentSegment s) {
        return switch (s.kind()) {
            case "cast" -> "Část " + s.number();
            case "hlava" -> "Hlava " + s.number();
            case "dil" -> "Díl " + s.number();
            case "oddil" -> "Oddíl " + s.number();
            case "par" -> "§ " + s.number();
            case "odst" -> "odst. " + s.number();
            case "pism" -> "písm. " + s.number() + ")";
            case "bod" -> "bod " + s.number();
            case "ppc" -> "ppc " + s.number();
            case "frag" -> "frag " + s.number();
            default -> s.kind() + " " + s.number();
        };
    }
}
