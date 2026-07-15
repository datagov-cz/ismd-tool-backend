package com.dia.ismdtoolbackend.utility.published;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The field vocabulary for a working-copy sync: which deviation keys exist, which may be accepted, and
 * how each one's live-NKD value is applied to a {@link ConceptEditModel}.
 *
 * <p>Keys are the {@code @JsonProperty} names on {@link PublishedConceptDeviationModel}, so the tokens the
 * FE sends back are exactly the ones it received. Single source of truth for the mapping — the deviation
 * model, the request DTO and the edit model would otherwise drift apart in three places.
 *
 * <p>A field is <em>deviating</em> iff its {@code PropertyDeviation} is non-null: the comparator only
 * populates fields that differ (it never emits {@code isDifferent=false}).
 */
@Component
public class WorkingCopySyncFields {

    /**
     * The concept-type field. ISMD cannot convert between TRIDA/VLASTNOST/VZTAH
     */
    public static final String TYPE_KEY = "typ";

    /**
     * Alt names are not syncable: the deviation carries {@code Map<String, Object>} (a language may hold
     * several alt labels), while {@code AltNameModel} holds one string per language. Accepting it would
     * silently drop every alt label after the first, so the key is left out of {@link #FIELDS} — it stays
     * visible in the deviation, just not acceptable.
     */
    public static final String ALT_NAME_KEY = "alternativní-název";

    /** One syncable characteristic: how to read its deviation, and how to apply NKD's value. */
    private record SyncableField(
            String key,
            Function<PublishedConceptDeviationModel, PublishedConceptDeviationModel.PropertyDeviation<?>> deviation,
            BiConsumer<ConceptEditModel, ConceptDetailModel> apply) {
    }

    private static final List<SyncableField> FIELDS = List.of(
            new SyncableField("název", PublishedConceptDeviationModel::getName,
                    (edit, nkd) -> edit.setNameModel(nameModel(nkd))),
            new SyncableField("definice", PublishedConceptDeviationModel::getDefinition,
                    (edit, nkd) -> edit.setDefinitionModel(definitionModel(nkd))),
            new SyncableField("popis", PublishedConceptDeviationModel::getDescription,
                    (edit, nkd) -> edit.setDescriptionModel(descriptionModel(nkd))),
            new SyncableField("identifikátor", PublishedConceptDeviationModel::getIdentifier,
                    (edit, nkd) -> edit.setIdentifier(nkd.getIdentifier())),
            new SyncableField("ekvivalentní-pojem", PublishedConceptDeviationModel::getExactMatches,
                    (edit, nkd) -> edit.setExactMatch(nkd.getExactMatches())),
            new SyncableField("definující-ustanovení-právního-předpisu",
                    PublishedConceptDeviationModel::getDefiningLegalSources,
                    (edit, nkd) -> edit.setDefiningLegalSource(nkd.getDefiningLegalSources())),
            new SyncableField("související-ustanovení-právního-předpisu",
                    PublishedConceptDeviationModel::getRelatedLegalSources,
                    (edit, nkd) -> edit.setRelatedLegalSource(nkd.getRelatedLegalSources())),
            new SyncableField("nadřazená-třída", PublishedConceptDeviationModel::getBroaderClasses,
                    WorkingCopySyncFields::applyBroaderClasses),
            new SyncableField("nadřazená-vlastnost", PublishedConceptDeviationModel::getBroaderProperties,
                    WorkingCopySyncFields::applyBroaderProperties),
            new SyncableField("nadřazený-vztah", PublishedConceptDeviationModel::getBroaderRelations,
                    WorkingCopySyncFields::applyBroaderRelations),
            new SyncableField("definiční-obor", PublishedConceptDeviationModel::getDomain,
                    WorkingCopySyncFields::applyDomain),
            new SyncableField("obor-hodnot", PublishedConceptDeviationModel::getRange,
                    WorkingCopySyncFields::applyRange),
            new SyncableField("způsob-sdílení-údajů", PublishedConceptDeviationModel::getSharingMethods,
                    WorkingCopySyncFields::applySharingMethods),
            new SyncableField("způsob-získání-údajů", PublishedConceptDeviationModel::getAcquisitionMethod,
                    WorkingCopySyncFields::applyAcquisitionMethod),
            new SyncableField("typ-obsahu-údajů", PublishedConceptDeviationModel::getContentType,
                    WorkingCopySyncFields::applyContentType),
            new SyncableField("je-ppdf", PublishedConceptDeviationModel::getIsPpdf,
                    WorkingCopySyncFields::applyIsPpdf),
            new SyncableField("ais", PublishedConceptDeviationModel::getAis,
                    WorkingCopySyncFields::applyAis),
            new SyncableField("agenda", PublishedConceptDeviationModel::getAgenda,
                    WorkingCopySyncFields::applyAgenda),
            new SyncableField("ustanovení-dokládající-neveřejnost-údaje",
                    PublishedConceptDeviationModel::getPrivacyProvisions,
                    WorkingCopySyncFields::applyPrivacyProvisions));

    /** Every key a client may send. Excludes {@link #TYPE_KEY}. */
    public Set<String> syncableKeys() {
        Set<String> keys = new LinkedHashSet<>();
        FIELDS.forEach(f -> keys.add(f.key()));
        return keys;
    }

    /**
     * The keys that actually deviate in {@code deviation} and may be accepted. {@link #TYPE_KEY} is
     * excluded even when it deviates, so it can never be counted or offered.
     */
    public Set<String> deviatingSyncableKeys(PublishedConceptDeviationModel deviation) {
        Set<String> keys = new LinkedHashSet<>();
        if (deviation == null) {
            return keys;
        }
        for (SyncableField f : FIELDS) {
            if (f.deviation().apply(deviation) != null) {
                keys.add(f.key());
            }
        }
        return keys;
    }

    /** Applies one accepted field's live-NKD value onto {@code edit}. Unknown keys are a no-op. */
    public void apply(String key, ConceptEditModel edit, ConceptDetailModel nkd) {
        for (SyncableField f : FIELDS) {
            if (f.key().equals(key)) {
                f.apply().accept(edit, nkd);
                return;
            }
        }
    }

    // --- value mapping ------------------------------------------------------------------------

    private static com.dia.ismdtoolbackend.models.NameModel nameModel(ConceptDetailModel nkd) {
        com.dia.ismdtoolbackend.models.NameModel m = new com.dia.ismdtoolbackend.models.NameModel();
        m.setName(nkd.getName());
        return m;
    }

    private static com.dia.ismdtoolbackend.models.concept.DefinitionModel definitionModel(ConceptDetailModel nkd) {
        com.dia.ismdtoolbackend.models.concept.DefinitionModel m =
                new com.dia.ismdtoolbackend.models.concept.DefinitionModel();
        m.setDefinition(nkd.getDefinition());
        return m;
    }

    private static com.dia.ismdtoolbackend.models.DescriptionModel descriptionModel(ConceptDetailModel nkd) {
        com.dia.ismdtoolbackend.models.DescriptionModel m = new com.dia.ismdtoolbackend.models.DescriptionModel();
        m.setDescription(nkd.getDescription());
        return m;
    }

    private static void applyDomain(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof PropertyConceptEditModel p) {
            p.setDomain(nkd.getDomain());
        } else if (edit instanceof RelationshipConceptEditModel r) {
            r.setDomain(nkd.getDomain());
        }
    }

    private static void applyRange(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof RelationshipConceptEditModel r) {
            r.setRange(nkd.getRange());
        } else if (edit instanceof PropertyConceptEditModel p) {
            // A VLASTNOST's range is an XSD datatype, carried as dataType on the edit model.
            p.setDataType(nkd.getRange());
        }
    }

    private static void applySharingMethods(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof ClassConceptEditModel c) {
            c.setSharingMethod(nkd.getSharingMethods());
        } else if (edit instanceof PropertyConceptEditModel p) {
            p.setSharingMethod(nkd.getSharingMethods());
        } else if (edit instanceof RelationshipConceptEditModel r) {
            r.setSharingMethod(nkd.getSharingMethods());
        }
    }

    private static void applyAcquisitionMethod(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof ClassConceptEditModel c) {
            c.setAcquisitionMethod(nkd.getAcquisitionMethod());
        } else if (edit instanceof PropertyConceptEditModel p) {
            p.setAcquisitionMethod(nkd.getAcquisitionMethod());
        } else if (edit instanceof RelationshipConceptEditModel r) {
            r.setAcquisitionMethod(nkd.getAcquisitionMethod());
        }
    }

    private static void applyContentType(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof ClassConceptEditModel c) {
            c.setContentType(nkd.getContentType());
        } else if (edit instanceof PropertyConceptEditModel p) {
            p.setContentType(nkd.getContentType());
        } else if (edit instanceof RelationshipConceptEditModel r) {
            r.setContentType(nkd.getContentType());
        }
    }

    private static void applyIsPpdf(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof ClassConceptEditModel c) {
            c.setIsInPPDF(nkd.getIsPpdf());
        } else if (edit instanceof PropertyConceptEditModel p) {
            p.setIsInPPDF(nkd.getIsPpdf());
        } else if (edit instanceof RelationshipConceptEditModel r) {
            r.setIsInPPDF(nkd.getIsPpdf());
        }
    }

    private static void applyAis(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof ClassConceptEditModel c) {
            c.setAgendaSystemCode(nkd.getAis());
        } else if (edit instanceof PropertyConceptEditModel p) {
            p.setAgendaSystemCode(nkd.getAis());
        } else if (edit instanceof RelationshipConceptEditModel r) {
            r.setAgendaSystemCode(nkd.getAis());
        }
    }

    private static void applyAgenda(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof ClassConceptEditModel c) {
            c.setAgendaCode(nkd.getAgenda());
        } else if (edit instanceof PropertyConceptEditModel p) {
            p.setAgendaCode(nkd.getAgenda());
        } else if (edit instanceof RelationshipConceptEditModel r) {
            r.setAgendaCode(nkd.getAgenda());
        }
    }

    private static void applyPrivacyProvisions(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof ClassConceptEditModel c) {
            c.setPrivacyProvisions(nkd.getPrivacyProvisions());
        } else if (edit instanceof PropertyConceptEditModel p) {
            p.setPrivacyProvisions(nkd.getPrivacyProvisions());
        } else if (edit instanceof RelationshipConceptEditModel r) {
            r.setPrivacyProvisions(nkd.getPrivacyProvisions());
        }
    }

    // A hierarchy key belongs to exactly one concept type; on any other type it is a no-op rather than a
    // cast failure, so a mismatched key can never take the request down.
    private static void applyBroaderClasses(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof ClassConceptEditModel c) {
            c.setBroaderConcept(nkd.getBroaderClasses());
        }
    }

    private static void applyBroaderProperties(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof PropertyConceptEditModel p) {
            p.setSuperProperty(nkd.getBroaderProperties());
        }
    }

    private static void applyBroaderRelations(ConceptEditModel edit, ConceptDetailModel nkd) {
        if (edit instanceof RelationshipConceptEditModel r) {
            r.setSuperRelation(nkd.getBroaderRelations());
        }
    }
}
