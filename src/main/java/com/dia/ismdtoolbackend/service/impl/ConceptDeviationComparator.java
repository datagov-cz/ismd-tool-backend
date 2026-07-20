package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class ConceptDeviationComparator {

    /**
     * Compares the 23 characteristics and stamps the result with which deviation case it is and which NKD
     * resource it was compared against — both cases share this one comparison, so the tag is what tells the
     * reader whether {@code localValue} is a stored copy or the user's own value.
     *
     * @param origin {@code LINK_TARGET} (stored copy vs NKD) or {@code WORKING_COPY} (own value vs own twin)
     * @param source the NKD resource compared against; its label is taken from {@code publishedConcept}
     */
    public PublishedConceptDeviationModel compareConceptDetails(
            OntologyDetailModel.ConceptDetailModel localConcept,
            OntologyDetailModel.ConceptDetailModel publishedConcept,
            SnapshotOrigin origin,
            String source) {
        PublishedConceptDeviationModel deviation = compareConceptDetails(localConcept, publishedConcept);
        deviation.setOrigin(origin);
        deviation.setSource(NkdConceptRefDto.builder()
                .iri(source)
                .label(labelOf(publishedConcept))
                .build());
        return deviation;
    }

    private static String labelOf(OntologyDetailModel.ConceptDetailModel concept) {
        if (concept == null || concept.getName() == null || concept.getName().isEmpty()) {
            return null;
        }
        String cs = concept.getName().get("cs");
        return cs != null ? cs : concept.getName().values().iterator().next();
    }

    public PublishedConceptDeviationModel compareConceptDetails(
            OntologyDetailModel.ConceptDetailModel localConcept,
            OntologyDetailModel.ConceptDetailModel publishedConcept) {

        PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder =
                PublishedConceptDeviationModel.builder();

        boolean hasDeviations = false;

        hasDeviations |= compareAndSetTypes(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetName(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetAlternativeName(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetDefinition(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetDescription(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetIdentifier(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetBroaderClasses(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetBroaderRelations(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetBroaderProperties(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetDomain(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetRange(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetExactMatches(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetDefiningLegalSources(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetRelatedLegalSources(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetDefiningNonLegalSources(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetRelatedNonLegalSources(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetSharingMethods(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetAcquisitionMethod(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetContentType(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetIsPpdf(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetAis(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetAgenda(localConcept, publishedConcept, builder);
        hasDeviations |= compareAndSetPrivacyProvisions(localConcept, publishedConcept, builder);

        builder.status(hasDeviations ?
                PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS :
                PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION);

        return builder.build();
    }

    private boolean compareAndSetTypes(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getTypes(), published.getTypes())) {
            builder.types(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getTypes())
                    .publishedValue(published.getTypes())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetName(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getName(), published.getName())) {
            builder.name(PublishedConceptDeviationModel.PropertyDeviation.<Map<String, String>>builder()
                    .localValue(local.getName())
                    .publishedValue(published.getName())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetAlternativeName(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getAlternativeName(), published.getAlternativeName())) {
            builder.alternativeName(PublishedConceptDeviationModel.PropertyDeviation.<Map<String, Object>>builder()
                    .localValue(local.getAlternativeName())
                    .publishedValue(published.getAlternativeName())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetDefinition(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getDefinition(), published.getDefinition())) {
            builder.definition(PublishedConceptDeviationModel.PropertyDeviation.<Map<String, String>>builder()
                    .localValue(local.getDefinition())
                    .publishedValue(published.getDefinition())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetDescription(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getDescription(), published.getDescription())) {
            builder.description(PublishedConceptDeviationModel.PropertyDeviation.<Map<String, String>>builder()
                    .localValue(local.getDescription())
                    .publishedValue(published.getDescription())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetIdentifier(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getIdentifier(), published.getIdentifier())) {
            builder.identifier(PublishedConceptDeviationModel.PropertyDeviation.<String>builder()
                    .localValue(local.getIdentifier())
                    .publishedValue(published.getIdentifier())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetBroaderClasses(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getBroaderClasses(), published.getBroaderClasses())) {
            builder.broaderClasses(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getBroaderClasses())
                    .publishedValue(published.getBroaderClasses())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetBroaderRelations(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getBroaderRelations(), published.getBroaderRelations())) {
            builder.broaderRelations(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getBroaderRelations())
                    .publishedValue(published.getBroaderRelations())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetBroaderProperties(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getBroaderProperties(), published.getBroaderProperties())) {
            builder.broaderProperties(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getBroaderProperties())
                    .publishedValue(published.getBroaderProperties())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetDomain(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getDomain(), published.getDomain())) {
            builder.domain(PublishedConceptDeviationModel.PropertyDeviation.<String>builder()
                    .localValue(local.getDomain())
                    .publishedValue(published.getDomain())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetRange(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getRange(), published.getRange())) {
            builder.range(PublishedConceptDeviationModel.PropertyDeviation.<String>builder()
                    .localValue(local.getRange())
                    .publishedValue(published.getRange())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetExactMatches(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getExactMatches(), published.getExactMatches())) {
            builder.exactMatches(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getExactMatches())
                    .publishedValue(published.getExactMatches())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetDefiningLegalSources(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getDefiningLegalSources(), published.getDefiningLegalSources())) {
            builder.definingLegalSources(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getDefiningLegalSources())
                    .publishedValue(published.getDefiningLegalSources())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetRelatedLegalSources(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getRelatedLegalSources(), published.getRelatedLegalSources())) {
            builder.relatedLegalSources(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getRelatedLegalSources())
                    .publishedValue(published.getRelatedLegalSources())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetDefiningNonLegalSources(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getDefiningNonLegalSources(), published.getDefiningNonLegalSources())) {
            builder.definingNonLegalSources(PublishedConceptDeviationModel.PropertyDeviation.<List<NonLegalSourceDto>>builder()
                    .localValue(local.getDefiningNonLegalSources())
                    .publishedValue(published.getDefiningNonLegalSources())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetRelatedNonLegalSources(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getRelatedNonLegalSources(), published.getRelatedNonLegalSources())) {
            builder.relatedNonLegalSources(PublishedConceptDeviationModel.PropertyDeviation.<List<NonLegalSourceDto>>builder()
                    .localValue(local.getRelatedNonLegalSources())
                    .publishedValue(published.getRelatedNonLegalSources())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetSharingMethods(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getSharingMethods(), published.getSharingMethods())) {
            builder.sharingMethods(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getSharingMethods())
                    .publishedValue(published.getSharingMethods())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetAcquisitionMethod(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getAcquisitionMethod(), published.getAcquisitionMethod())) {
            builder.acquisitionMethod(PublishedConceptDeviationModel.PropertyDeviation.<String>builder()
                    .localValue(local.getAcquisitionMethod())
                    .publishedValue(published.getAcquisitionMethod())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetContentType(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getContentType(), published.getContentType())) {
            builder.contentType(PublishedConceptDeviationModel.PropertyDeviation.<String>builder()
                    .localValue(local.getContentType())
                    .publishedValue(published.getContentType())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetIsPpdf(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getIsPpdf(), published.getIsPpdf())) {
            builder.isPpdf(PublishedConceptDeviationModel.PropertyDeviation.<Boolean>builder()
                    .localValue(local.getIsPpdf())
                    .publishedValue(published.getIsPpdf())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetAis(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getAis(), published.getAis())) {
            builder.ais(PublishedConceptDeviationModel.PropertyDeviation.<String>builder()
                    .localValue(local.getAis())
                    .publishedValue(published.getAis())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetAgenda(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getAgenda(), published.getAgenda())) {
            builder.agenda(PublishedConceptDeviationModel.PropertyDeviation.<String>builder()
                    .localValue(local.getAgenda())
                    .publishedValue(published.getAgenda())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private boolean compareAndSetPrivacyProvisions(
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published,
            PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder) {

        if (areDifferent(local.getPrivacyProvisions(), published.getPrivacyProvisions())) {
            builder.privacyProvisions(PublishedConceptDeviationModel.PropertyDeviation.<List<String>>builder()
                    .localValue(local.getPrivacyProvisions())
                    .publishedValue(published.getPrivacyProvisions())
                    .isDifferent(true)
                    .build());
            return true;
        }
        return false;
    }

    private <T> boolean areDifferent(T value1, T value2) {
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
    private boolean isNullOrEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof Collection<?> c) return c.isEmpty();
        if (value instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    private boolean compareListsIgnoreOrder(List<?> list1, List<?> list2) {
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

    private boolean compareMaps(Map<?, ?> map1, Map<?, ?> map2) {
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
