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
        Level level
) {
    public enum Level { LAW, VERSION, FRAGMENT }

    public record FragmentSegment(String kind, String number) {}

    public boolean isValid() {
        return level != null;
    }

    public boolean isFragment() {
        return level == Level.FRAGMENT;
    }
}
