package com.dia.ismdtoolbackend.utility.eli;

import java.time.LocalDate;
import java.util.List;

public record ParsedEli(
        String originalUrl,
        String domain,
        String eliPath,
        String lawIri,
        String versionIri,
        String fragmentIri,
        String lawNumber,
        Integer lawYear,
        String sbirkaCode,
        LocalDate versionDate,
        List<FragmentSegment> fragmentSegments,
        Level level,
        String container
) {
    public enum Level { LAW, VERSION, FRAGMENT }

    private static final String WEB_BASE = "https://e-sbirka.gov.cz/";

    public record FragmentSegment(String kind, String number) {}

    public boolean isValid() {
        return level != null;
    }

    public boolean isFragment() {
        return level == Level.FRAGMENT;
    }

    /** The law's text page on the e-Sbírka website; null for an invalid IRI. */
    public String lawWebUrl() {
        if (!isValid()) return null;
        return WEB_BASE + sbirkaCode + "/" + lawYear + "/" + lawNumber + "?zalozka=text";
    }

    /**
     * True when the IRI names a structural container itself ({@code .../dokument/poznamkypodcarou})
     * with no fragment path below it. Such an IRI is a citable target in its own right, so it parses
     * as a {@link Level#FRAGMENT} carrying no {@code fragmentSegments}.
     */
    public boolean isContainerRoot() {
        return isFragment() && container != null && fragmentSegments.isEmpty();
    }

    /**
     * True when the IRI names the document root ({@code .../dokument}) — the single parentless node
     * of a version's fragment tree, sitting above every container.
     */
    public boolean isDocumentRoot() {
        return isFragment() && container == null && fragmentSegments.isEmpty();
    }
}
