package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;
import com.dia.utility.UtilityMethods;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Strips trailing slashes from the IRI-valued fields of a concept payload, in place, so
 * validation, the RDF writers and the metadata all see the same corrected value.
 *
 * <p>Covers the concept identifier, legal sources, privacy provisions, exactMatch and the
 * references to other concepts. Non-legal source URLs and the code-list IRIs are left as
 * supplied — they name external resources whose address may end with a slash. Agenda and
 * AIS codes are corrected by {@link UtilityMethods} itself.
 */
public final class ConceptIriNormalizer {

    private ConceptIriNormalizer() {
    }

    /** Normalizes a create payload. */
    public static void normalize(ConceptCreateModel m) {
        if (m == null) return;
        m.setIdentifier(iri(m.getIdentifier()));
        m.setDefiningLegalSource(iris(m.getDefiningLegalSource()));
        m.setRelatedLegalSource(iris(m.getRelatedLegalSource()));
        m.setExactMatch(iris(m.getExactMatch()));

        if (m instanceof ClassConceptModel c) {
            c.setPrivacyProvisions(iris(c.getPrivacyProvisions()));
            c.setBroaderConcept(iris(c.getBroaderConcept()));
        } else if (m instanceof PropertyConceptModel p) {
            p.setPrivacyProvisions(iris(p.getPrivacyProvisions()));
            p.setDomain(iri(p.getDomain()));
            p.setSuperProperty(iris(p.getSuperProperty()));
            p.setDataType(iri(p.getDataType()));
        } else if (m instanceof RelationshipConceptModel r) {
            r.setPrivacyProvisions(iris(r.getPrivacyProvisions()));
            r.setDomain(iri(r.getDomain()));
            r.setRange(iri(r.getRange()));
            r.setSuperRelation(iris(r.getSuperRelation()));
        }
    }

    /** Normalizes an edit payload. */
    public static void normalize(ConceptEditModel m) {
        if (m == null) return;
        m.setIdentifier(iri(m.getIdentifier()));
        m.setDefiningLegalSource(iris(m.getDefiningLegalSource()));
        m.setRelatedLegalSource(iris(m.getRelatedLegalSource()));
        m.setExactMatch(iris(m.getExactMatch()));

        if (m instanceof ClassConceptEditModel c) {
            c.setPrivacyProvisions(iris(c.getPrivacyProvisions()));
            c.setBroaderConcept(iris(c.getBroaderConcept()));
        } else if (m instanceof PropertyConceptEditModel p) {
            p.setPrivacyProvisions(iris(p.getPrivacyProvisions()));
            p.setDomain(iri(p.getDomain()));
            p.setSuperProperty(iris(p.getSuperProperty()));
            p.setDataType(iri(p.getDataType()));
        } else if (m instanceof RelationshipConceptEditModel r) {
            r.setPrivacyProvisions(iris(r.getPrivacyProvisions()));
            r.setDomain(iri(r.getDomain()));
            r.setRange(iri(r.getRange()));
            r.setSuperRelation(iris(r.getSuperRelation()));
        }
    }

    /** Trims and strips a value that is an IRI; any other value is returned as supplied. */
    public static String iri(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return UtilityMethods.isValidIRI(trimmed) ? UtilityMethods.removeTrailingSlash(trimmed) : value;
    }

    private static List<String> iris(List<String> values) {
        if (values == null) return null;
        return values.stream().map(ConceptIriNormalizer::iri).collect(Collectors.toCollection(ArrayList::new));
    }
}
