package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;

import java.util.*;

/** Equality rules for the values of two concept details compared for deviations. */
final class DeviationValues {

    private DeviationValues() {
    }

    /**
     * ELI-list deviation: like {@link #areDifferent} for {@code List<String>}, but each IRI is
     * host-canonicalized first. We store legal-source/provision ELIs in canonical {@code .gov.cz} form
     * while NKD still publishes the legacy {@code .cz} host; without canonicalizing both sides the two
     * would deviate forever on the host alone. Malformed combined values (a {@code ';'}-joined pair from
     * upstream) are dropped from both sides — they are not valid ELIs, so a concept whose only NKD source
     * is such junk must not deviate forever. Order-insensitive; null/empty on both sides is not a deviation.
     */
    static boolean eliListsDiffer(List<String> local, List<String> published) {
        Set<String> localSet = canonicalizeEliSet(local);
        Set<String> publishedSet = canonicalizeEliSet(published);
        if (localSet.isEmpty() && publishedSet.isEmpty()) return false;
        return !localSet.equals(publishedSet);
    }

    static Set<String> canonicalizeEliSet(List<String> iris) {
        Set<String> out = new HashSet<>();
        if (iris == null) return out;
        for (String iri : iris) {
            if (iri == null) continue;
            String canonical = EsbirkaEliParser.canonicalizeHost(iri);
            // Drop malformed combined values (a ';'-joined ELI is not a single valid reference).
            if (canonical.indexOf(';') >= 0) continue;
            out.add(canonical);
        }
        return out;
    }

    static <T> boolean areDifferent(T value1, T value2) {
        // "Absent" and "empty collection" both mean "no values" — one side modelling a missing
        // collection as null and the other as an empty one is not a deviation. Without this, a field
        // the local extractor defaults to an empty list but NKD never populates would deviate forever.
        if (isNullOrEmpty(value1) && isNullOrEmpty(value2)) return false;
        if (value1 == null || value2 == null) return true;

        if (value1 instanceof List) {
            return compareListsIgnoreOrder((List<?>) value1, (List<?>) value2);
        } else if (value1 instanceof Map) {
            return !compareMaps((Map<?, ?>) value1, (Map<?, ?>) value2);
        }

        return !value1.equals(value2);
    }

    /** True for null, an empty collection, or an empty map — the three ways "no values" is modelled. */
    static boolean isNullOrEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof Collection<?> c) return c.isEmpty();
        if (value instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    static boolean compareListsIgnoreOrder(List<?> list1, List<?> list2) {
        if (list1.size() != list2.size()) return true;

        if (!list1.isEmpty() && !(list1.get(0) instanceof Map)) {
            return !new HashSet<>(list1).equals(new HashSet<>(list2));
        }

        if (!list1.isEmpty() && list1.get(0) instanceof Map) {
            @SuppressWarnings("unchecked")
            Set<Map<?, ?>> set1 = new HashSet<>((List<Map<?, ?>>) list1);
            @SuppressWarnings("unchecked")
            Set<Map<?, ?>> set2 = new HashSet<>((List<Map<?, ?>>) list2);
            return !set1.equals(set2);
        }

        return !list1.equals(list2);
    }

    static boolean compareMaps(Map<?, ?> map1, Map<?, ?> map2) {
        if (map1.size() != map2.size()) return false;

        for (Map.Entry<?, ?> entry : map1.entrySet()) {
            Object key = entry.getKey();
            Object val1 = entry.getValue();

            if (!map2.containsKey(key)) return false;

            Object val2 = map2.get(key);

            if (val1 instanceof List && val2 instanceof List) {
                if (compareListsIgnoreOrder((List<?>) val1, (List<?>) val2)) {
                    return false;
                }
            } else if (!Objects.equals(val1, val2)) {
                return false;
            }
        }

        return true;
    }
}
