package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchGroupDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberGroupModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaCzechCitationFormatter;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaHtmlText;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class EsbirkaServiceImpl implements EsbirkaService {

    static final int MAX_FRAGMENT_DEPTH = 10;
    static final int FRAGMENT_ROW_WARN_THRESHOLD = 5_000;

    /**
     * Row cap on the grouped search's act fetch. Group size is unbounded (~120 acts per low
     * číslo), so the group cap alone does not bound the response — 50 groups would stream
     * ~6 000 rows. Per-group display is capped in turn by {@link #MAX_LAWS_PER_GROUP}; the
     * true dataset-wide total still reaches the FE via {@code LawSearchGroupDto.count}.
     */
    static final int GROUPED_ROW_LIMIT = 600;

    /** Acts shown per group. The rest are reachable by narrowing the query (or by year). */
    static final int MAX_LAWS_PER_GROUP = 60;

    /**
     * Marker for the structural document containers of a version. Direct children of any
     * {@code <versionIri>/dokument/<container>} (norma / poznamkypodcarou / prilohy / …) are
     * tree roots. Used by {@link #assembleTree} to detect roots structurally rather than
     * against a hardcoded container list.
     */
    private static final String DOKUMENT_INFIX = "/dokument/";

    private final EsbirkaSparqlClient client;
    private final EsbirkaFragmentResolutionCache resolutionCache;

    /**
     * Self-reference through the Spring proxy so the two-arg {@link #getLawContent}'s
     * {@code @Cacheable} is honoured when the one-arg overload delegates to it. A direct
     * {@code this.getLawContent(ref, null)} call bypasses the proxy entirely, so the
     * latest-version path would re-run the whole ~2 MB fetch on every request.
     */
    private final EsbirkaService self;

    public EsbirkaServiceImpl(EsbirkaSparqlClient client,
                              EsbirkaFragmentResolutionCache resolutionCache,
                              @Lazy EsbirkaService self) {
        this.client = client;
        this.resolutionCache = resolutionCache;
        this.self = self;
    }

    // Keys join the raw values rather than Objects.hash(q, limit): that hash is
    // 31*(31 + q.hashCode()) + limit, so a needle differing by one code point collides with a
    // limit differing by up to 49 — e.g. hash("1", 32) == hash("2", 1). Measured across
    // q="1".."999" x limit=1..50 that is 34% of pairs, each silently serving another
    // search's results for the full 60-minute TTL. '\u0000' cannot occur in a URL query value,
    // so it is an unambiguous separator.
    @Override
    @Cacheable(cacheNames = "esbirkaLawSearch",
            key = "'flat:' + (#q == null ? '' : #q) + '\u0000' + #limit")
    public List<LawDto> searchLaws(String q, int limit) {
        List<LawModel> rows = client.searchLaws(q, limit);
        List<LawDto> out = new ArrayList<>(rows.size());
        for (LawModel m : rows) {
            out.add(toLawDto(m));
        }
        return out;
    }

    /**
     * Grouped law search, in two SPARQL steps: aggregate the matching předpis numbers
     * (capped at {@code limit} <em>groups</em>), then fetch the acts for exactly those
     * numbers.
     *
     * <p>A single row-capped query cannot do this. "49" matches 2 217 acts on the citation
     * (only 120 numbered 49 — the rest matched a year), and even číslo-scoped, "1"
     * prefix-matches 12 037 acts. Any row cap truncates mid-group and re-creates the bug
     * grouping exists to fix. Aggregating first bounds the work by the answer's size, and
     * gives true per-group counts rather than counts-of-what-fit.
     */
    @Override
    @Cacheable(cacheNames = "esbirkaLawSearch",
            key = "'grouped:' + (#q == null ? '' : #q) + '\u0000' + #limit")
    public LawSearchResultDto searchLawsGrouped(String q, int limit) {
        String needle = q == null ? null : q.trim();

        List<LawNumberGroupModel> numberGroups = client.searchLawNumberGroups(needle, limit);
        if (numberGroups.isEmpty()) {
            return LawSearchResultDto.builder()
                    .query(blankToNull(needle))
                    .ambiguous(false)
                    .totalMatches(0)
                    .truncated(false)
                    .groups(List.of())
                    .build();
        }

        List<String> cisla = new ArrayList<>(numberGroups.size());
        int totalMatches = 0;
        for (LawNumberGroupModel g : numberGroups) {
            cisla.add(g.cislo());
            totalMatches += g.pocet();
        }

        Map<String, List<LawDto>> byCislo = new HashMap<>();
        for (LawModel m : client.fetchLawsByNumbers(cisla, GROUPED_ROW_LIMIT)) {
            byCislo.computeIfAbsent(m.getCislo() == null ? "" : m.getCislo(),
                    k -> new ArrayList<>()).add(toLawDto(m));
        }

        List<LawSearchGroupDto> groups = new ArrayList<>(numberGroups.size());
        for (LawNumberGroupModel g : numberGroups) {
            List<LawDto> laws = byCislo.get(g.cislo());
            if (laws == null) {
                laws = new ArrayList<>();
            } else {
                // Sorted here rather than in SPARQL: the rows are re-bucketed by číslo above,
                // which discards any server-side číslo ordering anyway.
                laws.sort(NEWEST_ROK_FIRST);
                if (laws.size() > MAX_LAWS_PER_GROUP) {
                    laws = new ArrayList<>(laws.subList(0, MAX_LAWS_PER_GROUP));
                }
            }
            groups.add(LawSearchGroupDto.builder()
                    .cislo(g.cislo())
                    // Count is the dataset-wide total from step 1's aggregate — deliberately
                    // NOT laws.size(), which is only what was fetched and displayed.
                    .count(g.pocet())
                    .exactNumberMatch(needle != null && needle.equalsIgnoreCase(g.cislo()))
                    .laws(laws)
                    .build());
        }
        groups.sort(BEST_GROUP_FIRST);

        // The group cap is the only truncation left: more numbers match than we returned.
        boolean truncated = numberGroups.size() >= limit;

        return LawSearchResultDto.builder()
                .query(blankToNull(needle))
                .ambiguous(isAmbiguous(groups))
                .totalMatches(totalMatches)
                .truncated(truncated)
                .groups(groups)
                .build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Ambiguous = the user still has a choice to make: more than one group, or a single
     * group holding several acts (one number, many years). A single act is unambiguous,
     * and so is no match at all — there is nothing to disambiguate.
     */
    private static boolean isAmbiguous(List<LawSearchGroupDto> groups) {
        if (groups.isEmpty()) {
            return false;
        }
        return groups.size() > 1 || groups.get(0).getCount() > 1;
    }

    /** Newest rok first within a group; null roky sink to the bottom. */
    private static final Comparator<LawDto> NEWEST_ROK_FIRST =
            Comparator.comparing(LawDto::getRok,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(LawDto::getCitace, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Exact-number group first, then shortest číslo, then číslo ascending.
     *
     * <p>Shortest-first mirrors typing: "49" means 49 before 490 before 4900. Group size is
     * deliberately NOT a criterion — counts run 100+ for every low číslo, so ordering by
     * count would float whichever number happens to be most legislated to the top instead of
     * the one the user typed.
     */
    private static final Comparator<LawSearchGroupDto> BEST_GROUP_FIRST =
            Comparator.comparing(LawSearchGroupDto::isExactNumberMatch, Comparator.reverseOrder())
                    .thenComparing(g -> g.getCislo() == null ? 0 : g.getCislo().length())
                    .thenComparing(LawSearchGroupDto::getCislo,
                            Comparator.nullsLast(Comparator.naturalOrder()));

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
     * <p>Delegates through {@link #self} rather than calling the overload directly — a
     * {@code this.} call would not pass through the Spring proxy, leaving the two-arg
     * method's {@code @Cacheable} inert for every latest-version request.
     */
    @Override
    public LawContentDto getLawContent(String lawRef) {
        return self.getLawContent(lawRef, null);
    }

    /**
     * Whole-version content for a caller-chosen znění; null/blank {@code versionIri}
     * renders the latest version (má-poslední-znění).
     *
     * <p>A supplied IRI is accepted only when it appears in the resolved law's own version
     * list — host-shape validation alone would let a well-formed IRI from an unrelated act
     * through and render its text under this law's header.
     *
     * <p>Cached by normalized {@code number/year} <em>plus</em> the selected version — the
     * key MUST carry the version or every znění of a law would collide on one entry and
     * serve another version's body. Both the resolution and the (~2 MB) payload are
     * expensive, and a published version's text is immutable.
     */
    @Override
    @Cacheable(cacheNames = "esbirkaLawContent",
            key = "#root.target.normalizeLawRef(#lawRef) + '@' + (#versionIri == null ? '' : #versionIri)")
    public LawContentDto getLawContent(String lawRef, String versionIri) {
        NumberYear ny = parseNumberYear(lawRef);

        LawModel law = client.findLawByNumberYear(ny.number(), ny.year())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Právní akt č. " + ny.number() + "/" + ny.year() + " nebyl nalezen."));

        // Through the proxy so the esbirkaLawVersions cache is used: a user stepping through
        // N znění of one law takes N content-cache misses, and a direct client.fetchVersions
        // would re-issue the identical version-list query on every one of them.
        List<LawVersionDto> versionDtos = self.getVersions(law.getIri());
        LawVersionDto selected = selectVersion(versionDtos, versionIri, ny);

        String selectedIri = selected.getIri();
        List<FragmentModel> rows = client.fetchVersionContent(selectedIri);
        if (rows.size() > FRAGMENT_ROW_WARN_THRESHOLD) {
            log.warn("Version content for {} has {} rows (over {} threshold).",
                    selectedIri, rows.size(), FRAGMENT_ROW_WARN_THRESHOLD);
        }

        List<FragmentDto> fragments = assembleTree(rows, selectedIri);

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
     * latest. The requested IRI must be a member of {@code versions} — validating only its
     * host/shape would let an IRI from a different act render under this law's header.
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
        if (!SparqlIriValidator.isEsbirkaEliIri(versionIri)) {
            throw new IllegalArgumentException("Neplatný identifikátor znění právního aktu.");
        }
        for (LawVersionDto v : versions) {
            if (versionIri.equals(v.getIri())) {
                return v;
            }
        }
        throw new IllegalArgumentException("Znění " + versionIri
                + " nepatří k právnímu aktu č. " + ny.number() + "/" + ny.year() + ".");
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
     * Tree assembly. A fragment is a <em>root</em> when its parent is a structural document
     * container — {@code <versionIri>/dokument/<container>} for any container (norma = the
     * body, poznamkypodcarou = footnotes, prilohy = annexes, …); roots are detected
     * structurally. Multi-root is supported.
     */
    List<FragmentDto> assembleTree(List<FragmentModel> rows, String versionIri) {
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

        capDepth(roots, 1, versionIri);
        return roots;
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
     * Fragment citation, falling back to one derived from the IRI path segments when upstream
     * carries no citace-označení-fragmentu-znění-právního-aktu. Returns null rather than an empty string
     * when neither source yields a label.
     */
    private static String citationOrSegmentFallback(FragmentModel m) {
        String citation = m.getCitation();
        if (citation != null && !citation.isBlank()) {
            return citation;
        }
        ParsedEli parsed = EsbirkaEliParser.parse(m.getIri());
        if (!parsed.isFragment()) {
            return null;
        }
        String derived = EsbirkaCzechCitationFormatter
                .buildFragmentCitationFromSegments(parsed.fragmentSegments());
        return derived.isBlank() ? null : derived;
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
