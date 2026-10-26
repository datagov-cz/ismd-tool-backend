package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.NEVEREJNY_UDAJ_JSON_LD;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.TOP_JSON_LD;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.TSP_JSON_LD;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.VEREJNY_UDAJ_JSON_LD;
import static com.dia.ismdtoolbackend.service.impl.DeviationValues.areDifferent;
import static com.dia.ismdtoolbackend.service.impl.DeviationValues.eliListsDiffer;

@Component
public class ConceptDeviationComparator {

    /**
     * Compares the concept characteristics and stamps the result with which deviation case it is and which NKD
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
            OntologyDetailModel.ConceptDetailModel local,
            OntologyDetailModel.ConceptDetailModel published) {

        PublishedConceptDeviationModel.PublishedConceptDeviationModelBuilder builder =
                PublishedConceptDeviationModel.builder();

        boolean hasDeviations = false;

        // Name is intentionally not compared: an OFN concept's IRI is derived from its name, so an
        // IRI-matched pair shares a name by construction. A name deviation could only arise from NKD
        // publishing a stale IRI (data corruption) and is not actionable — syncing it would regenerate
        // the IRI and break the twin match. See WorkingCopySyncFields (název is not syncable either).
        hasDeviations |= set(builder::types, local.getTypes(), published.getTypes());
        hasDeviations |= set(builder::alternativeName, local.getAlternativeName(), published.getAlternativeName());
        hasDeviations |= set(builder::definition, local.getDefinition(), published.getDefinition());
        hasDeviations |= set(builder::description, local.getDescription(), published.getDescription());
        hasDeviations |= set(builder::identifier, local.getIdentifier(), published.getIdentifier());
        hasDeviations |= set(builder::broaderClasses, local.getBroaderClasses(), published.getBroaderClasses());
        hasDeviations |= set(builder::broaderRelations, local.getBroaderRelations(), published.getBroaderRelations());
        hasDeviations |= set(builder::broaderProperties, local.getBroaderProperties(), published.getBroaderProperties());
        hasDeviations |= set(builder::domain, local.getDomain(), published.getDomain());
        hasDeviations |= set(builder::range, local.getRange(), published.getRange());
        hasDeviations |= set(builder::exactMatches, local.getExactMatches(), published.getExactMatches());
        hasDeviations |= setEliList(builder::definingLegalSources, local.getDefiningLegalSources(), published.getDefiningLegalSources());
        hasDeviations |= setEliList(builder::relatedLegalSources, local.getRelatedLegalSources(), published.getRelatedLegalSources());
        hasDeviations |= set(builder::definingNonLegalSources, local.getDefiningNonLegalSources(), published.getDefiningNonLegalSources());
        hasDeviations |= set(builder::relatedNonLegalSources, local.getRelatedNonLegalSources(), published.getRelatedNonLegalSources());
        hasDeviations |= set(builder::sharingMethods, local.getSharingMethods(), published.getSharingMethods());
        hasDeviations |= set(builder::acquisitionMethod, local.getAcquisitionMethod(), published.getAcquisitionMethod());
        hasDeviations |= set(builder::contentType, local.getContentType(), published.getContentType());
        // NKD may omit je-ppdf entirely (null/undefined). Absent means "not in PPDF" — normalize both
        // sides to false so a local false never deviates against a missing NKD value.
        hasDeviations |= set(builder::isPpdf, Boolean.TRUE.equals(local.getIsPpdf()),
                Boolean.TRUE.equals(published.getIsPpdf()));
        // Read out of the typ list, and emitted only when at least one side carries the marker.
        hasDeviations |= set(builder::objectSubjectType, objectSubjectRole(local.getTypes()),
                objectSubjectRole(published.getTypes()));
        hasDeviations |= set(builder::isPublic, isPublic(local.getTypes()), isPublic(published.getTypes()));
        hasDeviations |= set(builder::ais, local.getAis(), published.getAis());
        hasDeviations |= set(builder::agenda, local.getAgenda(), published.getAgenda());
        hasDeviations |= setEliList(builder::privacyProvisions, local.getPrivacyProvisions(), published.getPrivacyProvisions());

        builder.status(hasDeviations ?
                PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS :
                PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION);

        return builder.build();
    }

    private static <T> boolean set(Consumer<PublishedConceptDeviationModel.PropertyDeviation<T>> field,
                                   T local, T published) {
        return stage(field, local, published, areDifferent(local, published));
    }

    private static boolean setEliList(Consumer<PublishedConceptDeviationModel.PropertyDeviation<List<String>>> field,
                                      List<String> local, List<String> published) {
        return stage(field, local, published, eliListsDiffer(local, published));
    }

    private static <T> boolean stage(Consumer<PublishedConceptDeviationModel.PropertyDeviation<T>> field,
                                     T local, T published, boolean differs) {
        if (!differs) {
            return false;
        }
        field.accept(PublishedConceptDeviationModel.PropertyDeviation.<T>builder()
                .localValue(local)
                .publishedValue(published)
                .isDifferent(true)
                .build());
        return true;
    }

    /** {@code "objekt"} / {@code "subjekt"} from the type list; {@code null} when it carries neither. */
    private static String objectSubjectRole(List<String> types) {
        if (types == null) {
            return null;
        }
        if (types.contains(TOP_JSON_LD)) {
            return "objekt";
        }
        if (types.contains(TSP_JSON_LD)) {
            return "subjekt";
        }
        return null;
    }

    /** {@code true}/{@code false} from the veřejný/neveřejný marker; {@code null} when it carries neither. */
    private static Boolean isPublic(List<String> types) {
        if (types == null) {
            return null;
        }
        if (types.contains(VEREJNY_UDAJ_JSON_LD)) {
            return Boolean.TRUE;
        }
        if (types.contains(NEVEREJNY_UDAJ_JSON_LD)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
