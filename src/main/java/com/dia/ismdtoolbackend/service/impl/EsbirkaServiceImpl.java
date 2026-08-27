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
import com.dia.ismdtoolbackend.utility.eli.EsbirkaHtmlText;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

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

    /**
     * Marker for the structural document containers of a version. Direct children of any
     * {@code <versionIri>/dokument/<container>} (norma / poznamkypodcarou / prilohy / …) are
     * tree roots. Used by {@link #assembleTree} to detect roots structurally rather than
     * against a hardcoded container list.
     */
    private static final String DOKUMENT_INFIX = "/dokument/";

    /** Kind of the document root, the single parentless node of a version's fragment tree. */
    private static final String DOKUMENT_KIND = "dokument";

    /** Kind of an unnumbered fragment: a text block when childless, a structural parent otherwise. */
    private static final String FRAG_KIND = "frag";

    /**
     * Display labels for the structural containers that sit between the document root and the
     * first citable unit. Upstream carries no citace-označení-fragmentu for these, and they are
     * not fragments in the ELI sense, so without this map the top two levels of the navigation
     * tree render blank.
     *
     * <p>Keyed by kind with any {@code :N} sibling suffix stripped — real IRIs include
     * {@code postfix:2}, {@code prilohy:4} and the like, which {@code parseKindFromIri} passes
     * through verbatim.
     */
    private static final Map<String, String> CONTAINER_LABELS = Map.of(
            "prefix", "Úvodní ustanovení",
            "norma", "Text předpisu",
            "novela", "Novelizační ustanovení",
            "prilohy", "Přílohy",
            "poznamkypodcarou", "Poznámky pod čarou",
            "postfix", "Závěrečná ustanovení",
            "zaver", "Závěr");

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

        List<FragmentDto> fragments = assembleTree(rows, versionIri, law.getCitace());

        return LawContentDto.builder()
                .lawIri(law.getIri())
                .citace(law.getCitace())
                .versionIri(versionIri)
                .versionEliPath(SparqlIriValidator.extractEsbirkaEliPath(versionIri))
                .versionDate(latest.getUcinnostOd())
                .versions(versionDtos)
                .fragments(fragments)
                .bodyHtml(renderBodyHtml(fragments))
                .build();
    }

    /**
     * Assemble the whole-version HTML body server-side from the fragment tree.
     *
     * <p>Each fragment is wrapped in a {@code <section>} carrying its ELI path, full IRI and kind
     * as data attributes ({@code data-eli} path + {@code data-iri} full IRI — FE hooks for
     * deep-linking / navigation / styling); the fragment's own {@code bodyHtml}
     * (null for structural fragments) precedes its children, so the output is a nested,
     * document-ordered tree. Order is the tree's order — the server-side {@code ORDER BY ?order}
     * preserved by {@link #assembleTree}.
     */
    private static String renderBodyHtml(List<FragmentDto> roots) {
        StringBuilder sb = new StringBuilder();
        for (FragmentDto root : roots) {
            appendFragmentHtml(sb, root);
        }
        return sb.toString();
    }

    private static void appendFragmentHtml(StringBuilder sb, FragmentDto node) {
        sb.append("<section data-eli=\"")
                .append(HtmlUtils.htmlEscape(nullToEmpty(node.getEliPath())))
                .append("\" data-iri=\"")
                .append(HtmlUtils.htmlEscape(nullToEmpty(node.getIri())))
                .append("\" data-kind=\"")
                .append(HtmlUtils.htmlEscape(nullToEmpty(node.getKind())))
                .append("\">");
        if (node.getBodyHtml() != null) {
            sb.append(node.getBodyHtml());
        }
        for (FragmentDto child : node.getChildren()) {
            appendFragmentHtml(sb, child);
        }
        sb.append("</section>");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
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

    /**
     * Cache-key normalization: trims and strips a trailing " Sb." so equivalent refs share a
     * cache entry. <strong>Not dead code</strong> — invoked reflectively by the {@code @Cacheable}
     * SpEL key on {@link #getLawContent} ({@code #root.target.normalizeLawRef(#lawRef)}); must
     * stay {@code public} for SpEL {@code #root.target} to resolve it. Covered by
     * {@code EsbirkaServiceImplTest.normalizeLawRefCollapsesEquivalentRefsToOneKey}.
     */
    public String normalizeLawRef(String lawRef) {
        NumberYear ny = parseNumberYear(lawRef);
        return ny.number() + "/" + ny.year();
    }

    record NumberYear(String number, int year) {}

    /**
     * Tree assembly without a law citation, so the document root stays unlabelled. Used by the
     * lean fragment-tree endpoint, which resolves no law metadata.
     */
    List<FragmentDto> assembleTree(List<FragmentModel> rows, String versionIri) {
        return assembleTree(rows, versionIri, null);
    }

    /**
     * Tree assembly. A fragment is a <em>root</em> when its parent is a structural document
     * container — {@code <versionIri>/dokument/<container>} for any container (norma = the
     * body, poznamkypodcarou = footnotes, prilohy = annexes, …); roots are detected
     * structurally. Multi-root is supported.
     *
     * <p>{@code lawCitation}, when present, labels the otherwise-blank {@code dokument} root.
     */
    List<FragmentDto> assembleTree(List<FragmentModel> rows, String versionIri, String lawCitation) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String dokumentPrefix = versionIri + DOKUMENT_INFIX;

        Map<String, FragmentDto> nodes = new HashMap<>(rows.size());
        for (FragmentModel m : rows) {
            nodes.put(m.getIri(), toFragmentDto(m));
        }

        List<FragmentDto> roots = new ArrayList<>();
        int orphanCount = 0;
        String firstOrphanParent = null;
        for (FragmentModel m : rows) {
            FragmentDto self = nodes.get(m.getIri());
            if (m.getParentIri() == null) {
                roots.add(self);
                continue;
            }
            String anchor = resolveAnchor(m.getParentIri(), nodes, dokumentPrefix);
            if (anchor == null) {
                // Unresolvable parent: surface as a root so the fragment's text is never
                // lost, but count it for the warn-log so the data anomaly stays visible.
                roots.add(self);
                orphanCount++;
                if (firstOrphanParent == null) {
                    firstOrphanParent = m.getParentIri();
                }
            } else if (ROOT_ANCHOR.equals(anchor)) {
                roots.add(self);
            } else {
                nodes.get(anchor).getChildren().add(self);
            }
        }

        if (orphanCount > 0) {
            log.warn("Surfaced {} fragment row(s) as roots for version {} because their parent IRI "
                            + "resolved to neither an existing node nor a dokument container "
                            + "(sample parent IRI: {}).",
                    orphanCount, versionIri, firstOrphanParent);
        }

        labelDocumentRoots(roots, lawCitation);
        markNavigable(roots);
        capDepth(roots, 1, versionIri);
        return roots;
    }

    /**
     * Flag the nodes that carry no navigable label.
     */
    private static void markNavigable(List<FragmentDto> nodes) {
        for (FragmentDto n : nodes) {
            boolean textOnly = FRAG_KIND.equals(n.getKind()) && n.getChildren().isEmpty();
            n.setNavigable(!textOnly);
            if (textOnly) {
                n.setCitation(null);
            }
            if (!n.getChildren().isEmpty()) {
                markNavigable(n.getChildren());
            }
        }
    }

    /**
     * Label the {@code dokument} root with the law citation. Upstream carries no citation for
     * it and it is not a fragment, so it would otherwise head the navigation tree blank.
     * Only unlabelled document roots are touched.
     */
    private static void labelDocumentRoots(List<FragmentDto> roots, String lawCitation) {
        if (lawCitation == null || lawCitation.isBlank()) {
            return;
        }
        for (FragmentDto root : roots) {
            if (DOKUMENT_KIND.equals(root.getKind()) && root.getCitation() == null) {
                root.setCitation("Zákon č. " + lawCitation);
            }
        }
    }

    /** Sentinel returned by {@link #resolveAnchor} when a fragment resolves to a tree root. */
    private static final String ROOT_ANCHOR = "ROOT";

    /**
     * Resolve where a fragment with the given parent IRI should attach.
     * <ul>
     *   <li>The IRI of an existing node → attach as that node's child.</li>
     *   <li>{@link #ROOT_ANCHOR} → the fragment is a tree root (parent is, or walks up to,
     *       a {@code <versionIri>/dokument/<container>} segment).</li>
     *   <li>{@code null} → unresolvable; caller surfaces it as a root (no text lost) and warns.</li>
     * </ul>
     * Walks the parent IRI up by path segments (stripping a trailing slash first), stopping
     * at the first existing node or {@code dokument/<container>} segment.
     */
    private static String resolveAnchor(String parentIri, Map<String, FragmentDto> nodes, String dokumentPrefix) {
        if (parentIri == null) {
            return null;
        }
        String cur = parentIri;
        while (true) {
            if (nodes.containsKey(cur)) {
                return cur;
            }
            if (isDokumentRoot(cur, dokumentPrefix)) {
                return ROOT_ANCHOR;
            }
            int slash = cur.lastIndexOf('/');
            if (slash <= 0) {
                return null;
            }
            cur = cur.substring(0, slash);
        }
    }

    /** True when {@code iri} is exactly {@code <versionIri>/dokument/<single-segment>}. */
    private static boolean isDokumentRoot(String iri, String dokumentPrefix) {
        if (!iri.startsWith(dokumentPrefix)) {
            return false;
        }
        String rest = iri.substring(dokumentPrefix.length());
        return !rest.isEmpty() && rest.indexOf('/') < 0;
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
        dto.setCitation(citationOrSegmentFallback(m));
        dto.setOrder(m.getOrder());
        dto.setBodyHtml(m.getBodyHtml());
        return dto;
    }

    /**
     * Fragment citation, in order of preference: the upstream
     * citace-označení-fragmentu-znění-právního-aktu, a structural-container label, then the
     * IRI path segments. Null when no source yields a label.
     *
     * <p>The segment fallback is load-bearing, not decorative: e-Sbírka deleted
     * citace-označení-fragmentu dataset-wide on 2026-08-24 and restored it later, serving
     * HTTP 200 with the predicate simply absent throughout. Upstream citations are therefore
     * the preferred source, never a guaranteed one — if the predicate disappears again,
     * §/Část labels degrade to segment-derived text instead of blanking the navigation.
     *
     * <p>Containers are resolved before the fragment check because they are not fragments in
     * the ELI sense — {@link EsbirkaEliParser} rejects them, and they make up the whole of the
     * navigation tree above the first citable unit. Their labels are independent of upstream
     * data, so they survive such an outage unchanged.
     */
    private static String citationOrSegmentFallback(FragmentModel m) {
        String citation = m.getCitation();
        if (citation != null && !citation.isBlank()) {
            return citation;
        }
        String containerLabel = containerLabel(m.getKind());
        if (containerLabel != null) {
            return containerLabel;
        }
        ParsedEli parsed = EsbirkaEliParser.parse(m.getIri());
        if (!parsed.isFragment()) {
            return null;
        }
        String derived = EsbirkaCzechCitationFormatter
                .buildFragmentCitationFromSegments(parsed.fragmentSegments());
        return derived.isBlank() ? null : derived;
    }

    /**
     * Label for a structural container kind, or null when the kind is not a container.
     * A {@code :N} suffix marks a repeated sibling (a second Přílohy block, say) and is
     * rendered as an ordinal so the siblings stay distinguishable in the navigation.
     */
    private static String containerLabel(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        int colon = kind.indexOf(':');
        String base = colon > 0 ? kind.substring(0, colon) : kind;
        String label = CONTAINER_LABELS.get(base);
        if (label == null) {
            return null;
        }
        return colon > 0 ? label + " (" + kind.substring(colon + 1) + ")" : label;
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
                    .fragmentBody(EsbirkaHtmlText.toPlainText(m.bodyHtml()))
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
