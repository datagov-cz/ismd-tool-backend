package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaFragmentHtml;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.dia.ismdtoolbackend.service.impl.EsbirkaFragmentTree.FRAGMENT_ROW_WARN_THRESHOLD;
import static com.dia.ismdtoolbackend.service.impl.EsbirkaFragmentTree.assembleTree;
import static com.dia.ismdtoolbackend.service.impl.EsbirkaLawReference.DEFAULT_SBIRKA;
import static com.dia.ismdtoolbackend.service.impl.EsbirkaLawReference.ELI_MARKER;
import static com.dia.ismdtoolbackend.service.impl.EsbirkaLawReference.NumberYear;
import static com.dia.ismdtoolbackend.service.impl.EsbirkaLawReference.canonicalize;
import static com.dia.ismdtoolbackend.service.impl.EsbirkaLawReference.parseNumberYear;
import static com.dia.ismdtoolbackend.service.impl.EsbirkaLawReference.requireValidIri;

/** Reads the whole-version content of a law, cached per law and version. */
@Component
@RequiredArgsConstructor
@Slf4j
class EsbirkaLawContentReader {

    private final EsbirkaClient client;
    private final EsbirkaLawVersionsReader versionsReader;

    /**
     * Whole-version content for a caller-chosen znění; null/blank {@code versionIri} renders
     * the latest version (má-poslední-znění). A supplied IRI is accepted only when it appears
     * in the resolved law's own version list.
     *
     * <p>{@code lawRef} accepts either a {@code číslo/rok} reference or a law/version/fragment
     * ELI IRI. An IRI at version level or below also supplies the znění, so callers holding an
     * IRI from {@code /law/versions}, {@code /resolve} or a stored legal source can pass it
     * alone; an explicit {@code versionIri} still wins over the one carried by the IRI.
     *
     * <p>The returned {@link LawContentDto} carries the law/version header and the full version
     * list (for an FE switcher) alongside the fragment tree, whose nodes each carry their
     * rendered HTML body for in-document browsing.
     *
     * <p>Cached by normalized {@code number/year} plus the selected version, so each znění gets
     * its own entry — both the resolution and the (~2 MB) content payload are expensive, and a
     * published version's text is immutable.
     */
    @Cacheable(cacheNames = "esbirkaLawContent",
            key = "#root.target.normalizeLawRef(#lawRef) + '@' "
                    + "+ #root.target.resolveContentVersionIri(#lawRef, #versionIri)",
            sync = true)
    public LawContentDto getLawContent(String lawRef, String versionIri) {
        NumberYear ny = parseNumberYear(lawRef);
        String effectiveVersionIri = resolveContentVersionIri(lawRef, versionIri);

        LawModel law = client.findLaw(ny.sbirka(), ny.number(), ny.year())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Právní akt č. " + ny.number() + "/" + ny.year() + " nebyl nalezen."));

        // The cached version list is shared across the N content-cache misses of a user stepping
        // through one law's znění.
        List<LawVersionDto> versionDtos = versionsReader.getVersions(law.getIri());
        LawVersionDto selected = selectVersion(versionDtos, effectiveVersionIri, ny);

        String selectedIri = selected.getIri();
        List<FragmentModel> rows = client.fetchVersionContent(selectedIri);
        if (rows.size() > FRAGMENT_ROW_WARN_THRESHOLD) {
            log.warn("Version content for {} has {} rows (over {} threshold).",
                    selectedIri, rows.size(), FRAGMENT_ROW_WARN_THRESHOLD);
        }

        List<FragmentDto> fragments = assembleTree(rows, selectedIri, law.getCitace());

        return LawContentDto.builder()
                .lawIri(law.getIri())
                .citace(law.getCitace())
                .versionIri(selectedIri)
                .versionEliPath(SparqlIriValidator.extractEsbirkaEliPath(selectedIri))
                .versionDate(selected.getUcinnostOd())
                .versionLatest(selected.isLatest())
                .versions(versionDtos)
                .fragments(fragments)
                .bodyHtml(renderBodyHtml(fragments))
                .build();
    }

    /**
     * Pick the znění to render: the caller's {@code versionIri} when supplied, else the
     * latest. The requested IRI must be a member of {@code versions}.
     */
    private static LawVersionDto selectVersion(List<LawVersionDto> versions,
                                               String versionIri,
                                               NumberYear ny) {
        if (versionIri == null || versionIri.isBlank()) {
            LawVersionDto latest = pickLatest(versions);
            if (latest == null) {
                throw new IllegalArgumentException(
                        "Právní akt č. " + ny.number() + "/" + ny.year() + " nemá žádné znění.");
            }
            return latest;
        }
        // Canonicalized before the membership scan, not just before validation: v.getIri()
        // comes from e-Sbírka and is always canonical, so a legacy-host IRI would otherwise
        // fail membership and report the misleading "nepatří k právnímu aktu".
        String canonical = requireValidIri(versionIri, "Neplatný identifikátor znění právního aktu.");
        for (LawVersionDto v : versions) {
            if (canonical.equals(v.getIri())) {
                return v;
            }
        }
        throw new IllegalArgumentException("Znění " + canonical
                + " nepatří k právnímu aktu č. " + ny.number() + "/" + ny.year() + ".");
    }

    /**
     * Assemble the whole-version HTML body server-side from the fragment tree.
     * Delegates to {@link EsbirkaFragmentHtml}, shared with the {@code /resolve} body fallback.
     */
    private static String renderBodyHtml(List<FragmentDto> roots) {
        return EsbirkaFragmentHtml.render(roots);
    }

    /**
     * Latest version = the one flagged via má-poslední-znění. Falls back to the first row
     * (fetchVersions orders newest-first by účinnost-znění-od) when no row is flagged —
     * defensive against upstream data without the flag.
     */
    private static LawVersionDto pickLatest(List<LawVersionDto> versions) {
        if (versions.isEmpty()) {
            return null;
        }
        for (LawVersionDto v : versions) {
            if (v.isLatest()) {
                return v;
            }
        }
        return versions.get(0);
    }

    /**
     * The znění to render: an explicit {@code versionIri} wins, else the one carried by
     * {@code lawRef} when it is a version- or fragment-level IRI, else null for "latest".
     *
     * <p>A fragment IRI yields its parent version — the content payload is whole-version, and
     * the fragment is addressable within the returned tree.
     *
     * <p>Called reflectively by the {@code @Cacheable} SpEL key on {@link #getLawContent} so
     * that a version IRI passed as {@code lawRef} keys its own entry instead of colliding with
     * the law's latest znění; it must stay {@code public}, and returns "" rather than null
     * because the key concatenates it.
     */
    public String resolveContentVersionIri(String lawRef, String versionIri) {
        if (versionIri != null && !versionIri.isBlank()) {
            return canonicalize(versionIri);
        }
        if (lawRef == null || !lawRef.contains(ELI_MARKER)) {
            return "";
        }
        ParsedEli parsed = EsbirkaEliParser.parse(lawRef.trim());
        return parsed.versionIri() == null ? "" : parsed.versionIri();
    }

    /**
     * Cache-key normalization: trims and strips a trailing " Sb." so equivalent refs share a
     * cache entry — an IRI and the číslo/rok it denotes collapse to the same key. A collection
     * other than Sb. prefixes the key, since a číslo/rok can exist in several collections. Called
     * reflectively by the {@code @Cacheable} SpEL key on {@link #getLawContent}, so it must
     * stay {@code public} despite having no Java caller.
     */
    public String normalizeLawRef(String lawRef) {
        NumberYear ny = parseNumberYear(lawRef);
        String numberYear = ny.number() + "/" + ny.year();
        return DEFAULT_SBIRKA.equals(ny.sbirka()) ? numberYear : ny.sbirka() + ":" + numberYear;
    }
}
