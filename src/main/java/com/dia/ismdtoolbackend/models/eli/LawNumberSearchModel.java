package com.dia.ismdtoolbackend.models.eli;

import java.util.List;

/**
 * Acts whose číslo starts with a prefix, grouped by číslo.
 *
 * @param groups      matched čísla with their act count; a count may be a lower bound when
 *                    {@code moreGroups} is set
 * @param laws        the acts of those groups
 * @param moreGroups  the source holds groups beyond {@code groups}
 */
public record LawNumberSearchModel(List<LawNumberGroupModel> groups, List<LawModel> laws, boolean moreGroups) {

    public static LawNumberSearchModel empty() {
        return new LawNumberSearchModel(List.of(), List.of(), false);
    }
}
