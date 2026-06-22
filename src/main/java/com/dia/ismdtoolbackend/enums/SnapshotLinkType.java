package com.dia.ismdtoolbackend.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * The logical relations through which a local concept may link to a published NKD concept and so
 * acquire a tracked local copy. This is the value stored in {@code NkdConceptSnapshotEntity.linkPredicate}
 * — the <em>logical link type</em>, not a raw RDF predicate IRI (broaderClass alone writes two RDF
 * predicates, so a 1:1 IRI map would be wrong).
 *
 * <p>Per spec, only these four are allowed; {@code domain}/{@code range}/related are excluded
 * (see {@code .planning/nkd-local-copy-snapshot-PLAN.md}).
 */
public enum SnapshotLinkType {

    /** Class concept's broader class — writes {@code rdfs:subClassOf} + the {@code nadřazená-třída} hierarchy prop. */
    BROADER_CLASS("broaderClass"),

    /** Property concept's super-property — writes {@code rdfs:subPropertyOf}. */
    SUPER_PROPERTY("superProperty"),

    /** Relationship concept's super-relation — writes {@code rdfs:subPropertyOf}. */
    SUPER_RELATION("superRelation"),

    /** Cross-vocabulary exact match — writes {@code skos:exactMatch}. */
    EXACT_MATCH("exactMatch");

    private final String value;

    SnapshotLinkType(String value) {
        this.value = value;
    }

    /** The token stored in {@code NkdConceptSnapshotEntity.linkPredicate} and accepted on the API. */
    public String value() {
        return value;
    }

    /** Resolves a stored/incoming token to a link type, if it is one of the allowed four. */
    public static Optional<SnapshotLinkType> fromValue(String value) {
        return Arrays.stream(values()).filter(t -> t.value.equals(value)).findFirst();
    }

    public static boolean isAllowed(String value) {
        return fromValue(value).isPresent();
    }
}
