package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
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
import com.dia.ismdtoolbackend.utility.eli.EsbirkaCzechCitationFormatter;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

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
     * Resolve a "number/year" law reference (e.g. "49/1997") to the full rendered
     * content of its latest version.
     *
     * <p>Resolution chain: parse number/year → exact law lookup (NOT a citation
     * substring match) → latest version (má-poslední-znění) → whole-version content
     * query. The returned {@link LawContentDto} carries the resolved law/version header
     * and the full version list (for an FE switcher) alongside the fragment tree, whose
     * nodes each carry their rendered HTML body for in-document browsing.
     *
     * <p>Cached by the normalized {@code number/year} key — both the resolution and the
     * (~2 MB) content payload are expensive, and a published version's text is immutable.
     */
    @Override
    @Cacheable(cacheNames = "esbirkaLawContent", key = "#root.target.normalizeLawRef(#lawRef)")
    public LawContentDto getLawContent(String lawRef) {
        NumberYear ny = parseNumberYear(lawRef);

        LawModel law = client.findLawByNumberYear(ny.number(), ny.year())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Právní akt č. " + ny.number() + "/" + ny.year() + " nebyl nalezen."));

        List<LawVersionModel> versions = client.fetchVersions(law.getIri());
        LawVersionModel latest = pickLatest(versions);
        if (latest == null) {
            throw new IllegalArgumentException(
                    "Právní akt č. " + ny.number() + "/" + ny.year() + " nemá žádné znění.");
        }

        String versionIri = latest.getIri();
        List<FragmentModel> rows = client.fetchVersionContent(versionIri);
        if (rows.size() > FRAGMENT_ROW_WARN_THRESHOLD) {
            log.warn("Version content for {} has {} rows (over {} threshold).",
                    versionIri, rows.size(), FRAGMENT_ROW_WARN_THRESHOLD);
        }

        List<LawVersionDto> versionDtos = new ArrayList<>(versions.size());
        for (LawVersionModel v : versions) {
            versionDtos.add(toVersionDto(v));
        }

        return LawContentDto.builder()
                .lawIri(law.getIri())
                .citace(law.getCitace())
                .versionIri(versionIri)
                .versionEliPath(SparqlIriValidator.extractEsbirkaEliPath(versionIri))
                .versionDate(latest.getUcinnostOd())
                .versions(versionDtos)
                .fragments(assembleTree(rows, versionIri))
                .build();
    }

    /**
     * Latest version = the one flagged via má-poslední-znění (LawVersionModel.latest).
     * Falls back to the first row (fetchVersions orders newest-first by účinnost-znění-od)
     * when no row is flagged — defensive against upstream data without the flag.
     */
    private static LawVersionModel pickLatest(List<LawVersionModel> versions) {
        if (versions.isEmpty()) {
            return null;
        }
        for (LawVersionModel v : versions) {
            if (v.isLatest()) {
                return v;
            }
        }
        return versions.get(0);
    }

    /**
     * Parse a "number/year" reference into its parts. Tolerates surrounding whitespace
     * and a trailing " Sb." Rejects anything that isn't exactly number/year — partial
     * input (e.g. "49") is the FE's cue to use /law/search instead.
     */
    static NumberYear parseNumberYear(String lawRef) {
        if (lawRef == null || lawRef.isBlank()) {
            throw new IllegalArgumentException(
                    "Zadejte referenci právního aktu ve tvaru číslo/rok (např. 49/1997).");
        }
        String cleaned = lawRef.trim();
        int sb = cleaned.indexOf(" Sb");
        if (sb > 0) {
            cleaned = cleaned.substring(0, sb).trim();
        }
        int slash = cleaned.indexOf('/');
        if (slash <= 0 || slash >= cleaned.length() - 1) {
            throw new IllegalArgumentException(
                    "Referenci zadejte ve tvaru číslo/rok (např. 49/1997).");
        }
        String number = cleaned.substring(0, slash).trim();
        String yearStr = cleaned.substring(slash + 1).trim();
        int year;
        try {
            year = Integer.parseInt(yearStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Rok v referenci musí být číslo (např. 49/1997).");
        }
        if (number.isBlank() || !number.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "Číslo předpisu v referenci musí být číselné (např. 49/1997).");
        }
        return new NumberYear(number, year);
    }

    /** Cache-key normalization: trims and strips a trailing " Sb." so equivalent refs share a cache entry. */
    public String normalizeLawRef(String lawRef) {
        NumberYear ny = parseNumberYear(lawRef);
        return ny.number() + "/" + ny.year();
    }

    record NumberYear(String number, int year) {}

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
        dto.setBodyHtml(m.getBodyHtml());
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
                    .displayLabel(EsbirkaCzechCitationFormatter.buildDisplayLabel(parsed, null))
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
                        .displayLabel(EsbirkaCzechCitationFormatter.buildDisplayLabel(parsed, null))
                        .enrichmentStatus(EnrichmentStatus.NOT_FOUND)
                        .build();
            }
            FragmentResolutionModel m = opt.get();
            return baseDtoBuilder(parsed)
                    .fragmentCitation(m.citation())
                    .fragmentBodyHtml(m.bodyHtml())
                    .versionValidUntil(m.versionValidUntil())
                    .isLatestVersion(m.isLatest())
                    .displayLabel(EsbirkaCzechCitationFormatter.buildDisplayLabel(parsed, m.citation()))
                    .enrichmentStatus(EnrichmentStatus.OK)
                    .build();
        } catch (SparqlEndpointUnavailableException e) {
            log.warn("e-Sbírka unavailable while resolving {}: {}", parsed.fragmentIri(), e.getMessage());
            return baseDtoBuilder(parsed)
                    .displayLabel(EsbirkaCzechCitationFormatter.buildDisplayLabel(parsed, null))
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
}
