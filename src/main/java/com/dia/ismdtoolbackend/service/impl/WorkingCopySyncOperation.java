package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static com.dia.constants.VocabularyConstants.NEVEREJNY_UDAJ;
import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE_LEGAL;
import static com.dia.constants.VocabularyConstants.USTANOVENI_NEVEREJNOST;
import static com.dia.constants.VocabularyConstants.VEREJNY_UDAJ;
import org.springframework.stereotype.Component;

/** Syncs a working copy with its published NKD twin, field by field, and severs it when only some are accepted. */
@Slf4j
@Component
@RequiredArgsConstructor
class WorkingCopySyncOperation {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final NkdSparqlClient nkdSparqlClient;
    private final ConceptDeviationComparator deviationComparator;
    private final WorkingCopySyncFields syncFields;
    private final ConceptAccessGuard accessGuard;
    private final ConceptEditOperation editOperation;
    private final ConceptDetailReader detailReader;

    /** Returns the concept's slug; the caller reads the detail once this transaction has committed. */
    String syncWorkingCopy(Long conceptId, List<String> fieldsToAccept) {
        ConceptMetadataEntity metadata = accessGuard.fetchAndValidateMetadata(conceptId, true);
        if (!Boolean.TRUE.equals(metadata.getIsPublished())) {
            throw new OntologyValidationException(
                    "Pojem není pracovní kopií publikovaného pojmu v NKD — není co synchronizovat.");
        }

        // ONE read of the NKD twin, used for BOTH the deviation and the values written. Reading twice
        // would decide the (irreversible) sever against one snapshot of NKD and write another, so a
        // change landing between the reads could sever a concept whose accepted set was in fact complete.
        Optional<OntologyDetailModel.ConceptDetailModel> nkdOpt =
                nkdSparqlClient.fetchPublishedConcept(metadata.getConceptIri());
        if (nkdOpt.isEmpty()) {
            throw new OntologyValidationException("Publikovaný pojem nebyl v NKD nalezen.");
        }
        OntologyDetailModel.ConceptDetailModel nkdConcept = nkdOpt.get();

        // The request carries field names only; every accepted value is re-derived here, never trusted
        // from the client.
        GetConceptDto current = detailReader.getConceptDetail(metadata.getSlug());
        PublishedConceptDeviationModel deviation = deviationComparator.compareConceptDetails(
                current.getConceptDetail(), nkdConcept, SnapshotOrigin.WORKING_COPY,
                metadata.getConceptIri());
        if (deviation.getStatus() != PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS) {
            throw new OntologyValidationException(
                    "Pojem se neliší od publikovaného pojmu v NKD, nebo NKD není dostupné — není co synchronizovat.");
        }

        Set<String> deviatingKeys = syncFields.deviatingSyncableKeys(deviation);
        Set<String> accepted = new LinkedHashSet<>(fieldsToAccept);
        validateAcceptedKeys(accepted, deviatingKeys);
        // Going private requires a valid provision; accepting the flag pulls the twin's provisions in.
        Set<String> effectiveAccepted = resolvePublicPrivateSync(accepted, nkdConcept);

        String slug = metadata.getSlug();   // an edit never changes the slug, only conceptIri/name
        ConceptEditModel editModel = buildEditModelFromAcceptedFields(metadata, effectiveAccepted, nkdConcept);
        // false: a name/identifier sync relocates the IRI too, but sync owns the sever decision below —
        // accepting ALL deviating fields must leave the concept a faithful, still-tracked working copy.
        editOperation.editConcept(conceptId, editModel, false);

        // Sever iff the user took only SOME of what deviates: they have chosen to diverge, so the concept
        // stops being a working copy. Accepting everything leaves it a faithful copy, still tracked.
        // Uses the EFFECTIVE accepted set: a privacy provision co-synced by accepting public/private is
        // part of that one coupled decision, so it counts as accepted (a private twin whose only other
        // deviation is its own required provision stays a faithful working copy, not severed).
        // Re-read after the edit: editConcept may have relocated the IRI (a name/identifier sync renames),
        // and this must flip the flag on the post-edit row, in the same transaction as the RDF delta.
        Set<String> acceptedForSever = intersect(effectiveAccepted, deviatingKeys);
        if (acceptedForSever.size() < deviatingKeys.size()) {
            ConceptMetadataEntity postEdit = accessGuard.fetchAndValidateMetadata(conceptId);
            postEdit.setIsPublished(false);
            conceptMetadataRepository.save(postEdit);
            log.info("Working copy {} severed: {} of {} deviating field(s) accepted → now a draft",
                    postEdit.getConceptIri(), acceptedForSever.size(), deviatingKeys.size());
        }

        return slug;
    }

    /** Every accepted key must be both known and actually deviating; {@code typ} is never acceptable. */
    void validateAcceptedKeys(Set<String> accepted, Set<String> deviatingKeys) {
        if (accepted.contains(WorkingCopySyncFields.TYPE_KEY)) {
            throw new OntologyValidationException(
                    "Typ pojmu nelze synchronizovat — ISMD nepodporuje převod mezi typy pojmů.");
        }
        Set<String> unknown = accepted.stream()
                .filter(k -> !syncFields.syncableKeys().contains(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unknown.isEmpty()) {
            throw new OntologyValidationException("Neznámé nebo nesynchronizovatelné vlastnosti: " + unknown);
        }
        Set<String> notDeviating = accepted.stream()
                .filter(k -> !deviatingKeys.contains(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!notDeviating.isEmpty()) {
            throw new OntologyValidationException("Tyto vlastnosti se neliší od NKD: " + notDeviating);
        }
    }

    static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.retainAll(b);
        return out;
    }

    /**
     * Public/private carries a validity condition: a concept is private only if it also has a valid privacy
     * provision (see {@code updateDataClassification}, which silently drops the neveřejný marker otherwise).
     * When the user accepts {@code veřejnost-údaje} and the NKD twin is private, this:
     * <ol>
     *   <li>verifies the twin actually carries a valid provision — 400 if not, so a private classification
     *       can never land as an invalid (silently-public) shape;</li>
     *   <li>co-syncs {@code ustanovení-dokládající-neveřejnost-údaje} so the accepted "make it private" is
     *       self-consistent — the flag and its required provision are written together.</li>
     * </ol>
     * Going public needs neither. Returns the effective accepted set (with the provision key added when
     * required); the user's original {@code accepted} set is left untouched so the sever arithmetic is
     * unaffected by the auto-added key.
     */
    Set<String> resolvePublicPrivateSync(
            Set<String> accepted, OntologyDetailModel.ConceptDetailModel nkd) {
        if (!accepted.contains(WorkingCopySyncFields.IS_PUBLIC_KEY)) {
            return accepted;
        }
        Boolean nkdPublic = WorkingCopySyncFields.publicFromTypes(nkd.getTypes());
        if (!Boolean.FALSE.equals(nkdPublic)) {
            return accepted;   // going public (or no marker) — no provision needed
        }
        // Going private: the twin MUST carry a valid provision, or the private shape would be invalid.
        List<String> nkdProvisions = nkd.getPrivacyProvisions();
        boolean hasValidProvision = nkdProvisions != null
                && nkdProvisions.stream().anyMatch(p -> p != null && !p.trim().isEmpty());
        if (!hasValidProvision) {
            throw new OntologyValidationException(
                    "Nelze synchronizovat neveřejnost údaje: publikovaný pojem v NKD neobsahuje platné "
                            + "ustanovení dokládající neveřejnost.");
        }
        Set<String> effective = new LinkedHashSet<>(accepted);
        effective.add("ustanovení-dokládající-neveřejnost-údaje");
        return effective;
    }

    /**
     * An edit model of the concept's own type carrying <strong>only</strong> the accepted fields, so the
     * normal edit path's null early-returns leave everything else untouched.
     *
     * <p><strong>Except data classification.</strong> {@code updateDataClassification} has no null guard:
     * it strips the veřejný/neveřejný type unconditionally and re-adds it only for a non-null
     * {@code isPublic}, so leaving it null would silently DELETE the classification. Both it and
     * {@code privacyProvisions} are therefore carried through at their CURRENT local values unless the user
     * accepted them — for this field, null means "drop it", not "don't touch it".
     */
    ConceptEditModel buildEditModelFromAcceptedFields(
            ConceptMetadataEntity metadata, Set<String> accepted, OntologyDetailModel.ConceptDetailModel nkd) {
        ConceptEditModel editModel = newEditModelFor(metadata.getConceptType());
        editModel.setConceptType(metadata.getConceptType().name());

        carryCurrentDataClassification(editModel, metadata);
        accepted.forEach(key -> syncFields.apply(key, editModel, nkd));
        return editModel;
    }

    ConceptEditModel newEditModelFor(ConceptType conceptType) {
        return switch (conceptType) {
            case TRIDA -> new ClassConceptEditModel();
            case VLASTNOST -> new PropertyConceptEditModel();
            case VZTAH -> new RelationshipConceptEditModel();
            case KONCEPT -> throw new OntologyValidationException(
                    "Pojem typu KONCEPT nelze editovat, a tedy ani synchronizovat.");
        };
    }

    /**
     * Seeds the edit model with the concept's current classification, read from the graph the same way
     * {@code updateDataClassification} reads it, so a sync that does not accept these fields is a no-op on
     * them instead of a deletion. An accepted key overwrites the seed afterwards.
     *
     * <p>This graph read is separate from the one {@link ConceptEditOperation#editConcept} performs, which is safe:
     * both run inside the caller's transaction, and {@code editConcept} takes the concept
     * row lock before its own read, so a concurrent edit of this concept cannot land between them.
     * Do not reuse this read-then-edit shape where that lock is not held.
     */
    void carryCurrentDataClassification(
            ConceptEditModel editModel, ConceptMetadataEntity metadata) {
        Model model = jenaTDB2Repository.fetchGraph(metadata.getGraphName());
        Resource concept = model.getResource(metadata.getConceptIri());
        Boolean isPublic = currentIsPublic(model, concept);
        List<String> provisions = currentPrivacyProvisions(model, concept);

        if (editModel instanceof ClassConceptEditModel c) {
            c.setIsPublic(isPublic);
            c.setPrivacyProvisions(provisions);
        } else if (editModel instanceof PropertyConceptEditModel p) {
            p.setIsPublic(isPublic);
            p.setPrivacyProvisions(provisions);
        } else if (editModel instanceof RelationshipConceptEditModel r) {
            r.setIsPublic(isPublic);
            r.setPrivacyProvisions(provisions);
        }
    }

    /** True/false from the veřejný/neveřejný rdf:type; null when the concept carries neither. */
    Boolean currentIsPublic(Model model, Resource concept) {
        if (concept.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ))) {
            return Boolean.TRUE;
        }
        if (concept.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ))) {
            return Boolean.FALSE;
        }
        return null;
    }

    List<String> currentPrivacyProvisions(Model model, Resource concept) {
        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        List<String> provisions = new ArrayList<>();
        StmtIterator it = model.listStatements(concept, provisionProperty, (RDFNode) null);
        try {
            while (it.hasNext()) {
                RDFNode object = it.next().getObject();
                provisions.add(object.isURIResource() ? object.asResource().getURI() : object.toString());
            }
        } finally {
            it.close();
        }
        return provisions;
    }
}
