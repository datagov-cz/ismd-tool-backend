package com.dia.ismdtoolbackend.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * The logical relations through which a local concept may link to a published NKD concept and so
 * acquire a tracked local copy. This is the value stored in {@code NkdConceptSnapshotEntity.linkPredicate}
 * — the <em>logical link type</em>, not a raw RDF predicate IRI (broaderClass alone writes two RDF
 * predicates, so a 1:1 IRI map would be wrong).
 *
 * <p>{@code domain} is excluded for every type, and {@code range} for a VLASTNOST (whose range is an
 * XSD datatype, not a concept). A <strong>VZTAH's</strong> {@code range} IS a class, so it may point at a
 * published NKD concept and is snapshotted as {@link #RANGE_TARGET} — that is what lets a diagram draw a
 * relationship from an owned class to an NKD one without ever writing NKD's own triples.
 */
public enum SnapshotLinkType {

    /** Class concept's broader class — writes {@code rdfs:subClassOf} + the {@code nadřazená-třída} hierarchy prop. */
    BROADER_CLASS("broaderClass"),

    /** Property concept's super-property — writes {@code rdfs:subPropertyOf}. */
    SUPER_PROPERTY("superProperty"),

    /** Relationship concept's super-relation — writes {@code rdfs:subPropertyOf}. */
    SUPER_RELATION("superRelation"),

    /** Cross-vocabulary exact match — writes {@code skos:exactMatch}. */
    EXACT_MATCH("exactMatch"),

    /**
     * A relationship concept's object class — writes {@code rdfs:range}. VZTAH only: a VLASTNOST's range
     * is a literal datatype, so there is no concept to snapshot, and a {@code domain} pointing at a
     * published concept stays invalid input for every type.
     */
    RANGE_TARGET("rangeTarget");

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
