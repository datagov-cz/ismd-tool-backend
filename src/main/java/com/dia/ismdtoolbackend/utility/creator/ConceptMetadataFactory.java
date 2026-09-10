package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.utility.UtilityMethods;

import java.util.Set;
import java.util.function.Predicate;

/** Builds draft metadata only; the service owns ontology lookup, persistence and transactions. */
public final class ConceptMetadataFactory {
    private ConceptMetadataFactory() {}

    public static ConceptMetadataEntity create(ConceptCreateModel model, String conceptIri, String userId,
                                               OntologyMetadataEntity ontology, Predicate<String> slugExists,
                                               Set<String> reservedSlugs) {
        String base = UtilityMethods.extractNameFromIRI(ontology.getGraphName()) + "-"
                + UtilityMethods.extractNameFromIRI(conceptIri);
        String slug = base;
        int suffix = 1;
        while (reservedSlugs.contains(slug) || slugExists.test(slug)) slug = base + "-" + suffix++;
        reservedSlugs.add(slug);

        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setSlug(slug);
        entity.setConceptName(nameForMetadata(model.getNameModel()));
        entity.setConceptType(model.getConceptTypeEnum());
        entity.setConceptIri(conceptIri);
        entity.setGraphName(ontology.getGraphName());
        entity.setUserId(userId);
        entity.setIsPublished(false);
        entity.setInTezaurus(model.getInTezaurus());
        entity.setOntologyMetadata(ontology);
        return entity;
    }

    public static String nameForMetadata(NameModel model) {
        if (model == null || model.getName() == null || model.getName().isEmpty()) return "";
        var names = model.getName();
        return names.containsKey("cs") ? names.get("cs") : names.values().iterator().next();
    }
}
