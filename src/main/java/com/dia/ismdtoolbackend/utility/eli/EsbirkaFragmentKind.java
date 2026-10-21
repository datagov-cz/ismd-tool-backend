package com.dia.ismdtoolbackend.utility.eli;

/**
 * Fragment kind from the last segment of an e-Sbírka fragment IRI:
 * {@code .../par_122/odst_4/pism_g} → {@code pism}, {@code .../dokument/norma} → {@code norma}.
 */
public final class EsbirkaFragmentKind {

    private EsbirkaFragmentKind() {
    }

    /** @return the kind, or {@code "unknown"} when the IRI has no last segment */
    public static String fromIri(String iri) {
        int slash = iri.lastIndexOf('/');
        if (slash < 0 || slash >= iri.length() - 1) return "unknown";
        String last = iri.substring(slash + 1);
        int underscore = last.indexOf('_');
        return underscore > 0 ? last.substring(0, underscore) : last;
    }
}
