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

    /** Row cap on the grouped search's act fetch; group size itself is unbounded. */
    static final int GROUPED_ROW_LIMIT = 600;

    /** Acts shown per group. The rest are reachable by narrowing the query (or by year). */
    static final int MAX_LAWS_PER_GROUP = 60;

    /** Infix of a version's structural document containers, {@code <versionIri>/dokument/<container>}. */
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

    /** Self-reference through the Spring proxy, so internal calls still hit {@code @Cacheable}. */
    private final EsbirkaService self;

    public EsbirkaServiceImpl(EsbirkaSparqlClient client,
                              EsbirkaFragmentResolutionCache resolutionCache,
                              @Lazy EsbirkaService self) {
        this.client = client;
        this.resolutionCache = resolutionCache;
        this.self = self;
    }

    // Keys join the raw values rather than Objects.hash(q, limit), which collides across
    // needle/limit pairs and would serve another search's results. '\u0000' cannot occur in a URL query value,
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
     * numbers. The aggregate also yields the true per-group counts.
     */
    @Override
    @Cacheable(cacheNames = "esbirkaLawSearch",
            key = "'grouped:' + (#q == null ? '' : #q) + '\u0000' + #limit")
    public LawSearchResultDto searchLawsGrouped(String q, int limit) {
        String needle = q == null ? null : q.trim();
        GroupedNeedle parsed = splitGroupedNeedle(needle);

        List<LawNumberGroupModel> numberGroups =
                client.searchLawNumberGroups(parsed.cislo(), parsed.rok(), limit);
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
        for (LawModel m : client.fetchLawsByNumbers(cisla, parsed.rok(), GROUPED_ROW_LIMIT)) {
            byCislo.computeIfAbsent(m.getCislo() == null ? "" : m.getCislo(),
                    k -> new ArrayList<>()).add(toLawDto(m));
        }

        List<LawSearchGroupDto> groups = new ArrayList<>(numberGroups.size());
        for (LawNumberGroupModel g : numberGroups) {
            List<LawDto> laws = byCislo.get(g.cislo());
            if (laws == null) {
                laws = new ArrayList<>();
            } else {
                // Sorted here, not in SPARQL: re-bucketing by číslo above discards any
                // server-side ordering.
                laws.sort(NEWEST_ROK_FIRST);
                if (laws.size() > MAX_LAWS_PER_GROUP) {
                    laws = new ArrayList<>(laws.subList(0, MAX_LAWS_PER_GROUP));
                }
            }
            groups.add(LawSearchGroupDto.builder()
                    .cislo(g.cislo())
                    // Dataset-wide total from step 1's aggregate, not laws.size().
                    .count(g.pocet())
                    // Compared against the číslo part alone: "49/1997" pins číslo 49 exactly,
                    // and matching the raw needle would make this permanently false.
                    .exactNumberMatch(parsed.cislo() != null && parsed.cislo().equalsIgnoreCase(g.cislo()))
                    .laws(laws)
                    .build());
        }
        groups.sort(BEST_GROUP_FIRST);

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

    /** A grouped-search needle split into its číslo prefix and optional rok prefix. */
    record GroupedNeedle(String cislo, String rok) {}

    /**
     * Split a grouped-search needle on '/' into číslo and rok, tolerating a trailing " Sb." and
     * surrounding whitespace. Both halves are prefixes, and both may be blank.
     *
     * <p>Deliberately tolerant, unlike {@link #parseNumberYear}: this runs per keystroke, so
     * every intermediate state a user types — "49", "49/", "49/19", "49/1997 Sb." — must return
     * results rather than throw. Anything that is not a číslo/rok shape (letters, a second
     * slash) is passed through as a číslo prefix, where it simply matches nothing.
     */
    static GroupedNeedle splitGroupedNeedle(String needle) {
        if (needle == null || needle.isBlank()) {
            return new GroupedNeedle(null, null);
        }
        String cleaned = needle.trim();
        int sb = cleaned.indexOf(" Sb");
        if (sb > 0) {
            cleaned = cleaned.substring(0, sb).trim();
        }
        int slash = cleaned.indexOf('/');
        if (slash < 0) {
            return new GroupedNeedle(blankToNull(cleaned), null);
        }
        String cislo = cleaned.substring(0, slash).trim();
        String rok = cleaned.substring(slash + 1).trim();
        // A non-numeric year would match no act anyway, but as a prefix filter it would also
        // silently drop the číslo half's results. Ignoring it keeps the číslo results visible.
        if (!rok.isEmpty() && !rok.chars().allMatch(Character::isDigit)) {
            rok = null;
        }
        return new GroupedNeedle(blankToNull(cislo), blankToNull(rok));
    }

    /**
     * Ambiguous = the user still has a choice to make: more than one group, or a single group
     * holding several acts. A single act, or no match, is unambiguous.
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
     * Exact-number group first, then shortest číslo, then číslo ascending — mirroring how
     * people type ("49" before "490" before "4900"). Group size is not a criterion.
     */
    private static final Comparator<LawSearchGroupDto> BEST_GROUP_FIRST =
            Comparator.comparing(LawSearchGroupDto::isExactNumberMatch, Comparator.reverseOrder())
                    .thenComparing(g -> g.getCislo() == null ? 0 : g.getCislo().length())
                    .thenComparing(LawSearchGroupDto::getCislo,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Rewrite a legacy e-Sbírka host to the canonical one, leaving everything else untouched.
     *
     * <p>Every e-Sbírka IRI entering this service passes through here before it is validated,
     * compared or cached, so all endpoints accept the same host spellings that {@code /resolve}
     * and the concept write paths already accept. Called reflectively by the {@code @Cacheable}
     * SpEL keys below, so it must stay {@code public}.
     */
    public String canonicalizeEsbirkaIri(String iri) {
        return iri == null ? null : EsbirkaEliParser.canonicalizeHost(iri.trim());
    }

    /** Canonicalize, then validate — a legacy host is a spelling, not an invalid identifier. */
    private static String requireValidIri(String iri, String message) {
        String canonical = iri == null ? null : EsbirkaEliParser.canonicalizeHost(iri.trim());
        if (!SparqlIriValidator.isEsbirkaEliIri(canonical)) {
            throw new IllegalArgumentException(message);
        }
        return canonical;
    }

    // Keyed on the canonical form so a legacy-host IRI shares the entry with its canonical
    // twin rather than issuing an identical second query under its own key.
    @Override
    @Cacheable(cacheNames = "esbirkaLawVersions",
            key = "#root.target.canonicalizeEsbirkaIri(#lawIri)")
    public List<LawVersionDto> getVersions(String lawIri) {
        String iri = requireValidIri(lawIri, "Neplatný identifikátor právního aktu.");
        List<LawVersionModel> rows = client.fetchVersions(iri);
        List<LawVersionDto> out = new ArrayList<>(rows.size());
        for (LawVersionModel m : rows) {
            out.add(toVersionDto(m));
        }
        return out;
    }

    @Override
    public List<FragmentDto> getFragments(String versionIri) {
        String iri = requireValidIri(versionIri, "Neplatný identifikátor znění právního aktu.");
        List<FragmentModel> rows = client.fetchFragments(iri);
        if (rows.size() > FRAGMENT_ROW_WARN_THRESHOLD) {
            log.warn("Fragment tree for {} has {} rows (over {} threshold).",
                    iri, rows.size(), FRAGMENT_ROW_WARN_THRESHOLD);
        }
        return assembleTree(rows, iri);
    }

    /**
     * Resolve a "number/year" law reference (e.g. "49/1997") to the full rendered content of
     * its latest version: parse the ref → exact law lookup → latest znění → content query.
     * Delegates through {@link #self} so the overload's {@code @Cacheable} applies.
     */
    @Override
    public LawContentDto getLawContent(String lawRef) {
        return self.getLawContent(lawRef, null);
    }

    /**
     * Whole-version content for a caller-chosen znění; null/blank {@code versionIri} renders
     * the latest version (má-poslední-znění). A supplied IRI is accepted only when it appears
     * in the resolved law's own version list.
     *
     * <p>Cached by normalized {@code number/year} plus the selected version, so each znění
     * gets its own entry.
     */
    @Override
    @Cacheable(cacheNames = "esbirkaLawContent",
            key = "#root.target.normalizeLawRef(#lawRef) + '@' "
                    + "+ (#versionIri == null ? '' : #root.target.canonicalizeEsbirkaIri(#versionIri))")
    public LawContentDto getLawContent(String lawRef, String versionIri) {
        NumberYear ny = parseNumberYear(lawRef);

        LawModel law = client.findLawByNumberYear(ny.number(), ny.year())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Právní akt č. " + ny.number() + "/" + ny.year() + " nebyl nalezen."));

        // Through the proxy so the esbirkaLawVersions cache is used across the N content-cache
        // misses of a user stepping through one law's znění.
        List<LawVersionDto> versionDtos = self.getVersions(law.getIri());
        LawVersionDto selected = selectVersion(versionDtos, versionIri, ny);

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
     * Assemble the whole-version HTML body from the fragment tree: each fragment becomes a
     * {@code <section>} carrying its ELI path, IRI and kind as data attributes, with its own
     * {@code bodyHtml} (null for structural fragments) ahead of its children.
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
     * Latest version = the one flagged via má-poslední-znění, falling back to the first row
     * (fetchVersions orders newest-first by účinnost-znění-od) when no row is flagged.
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
     * Parse a "number/year" reference into its parts, tolerating surrounding whitespace and a
     * trailing " Sb.". Partial input (e.g. "49") is rejected; callers use /law/search for that.
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
     * cache entry. Called reflectively by the {@code @Cacheable} SpEL key on
     * {@link #getLawContent}, so it must stay {@code public} despite having no Java caller.
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
     * Tree assembly. A fragment is a root when its parent is a structural document container
     * ({@code <versionIri>/dokument/<container>}); multiple roots are supported.
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
                // Unresolvable parent: surface as a root so no text is lost, and count it
                // for the warn-log.
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
     * Resolve where a fragment with the given parent IRI should attach, walking the IRI up by
     * path segments: the IRI of an existing node (attach as its child), {@link #ROOT_ANCHOR}
     * (a {@code dokument/<container>} segment, so a tree root), or {@code null} (unresolvable).
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
