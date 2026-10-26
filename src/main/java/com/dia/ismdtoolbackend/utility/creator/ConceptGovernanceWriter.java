package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.URIGenerator;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;

import java.util.List;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Writes agenda, AIS, sharing, acquisition and content type, the PPDF flag and governance privacy provisions. */
@Slf4j
final class ConceptGovernanceWriter {

    private final OntModel ontModel;
    private final URIGenerator uriGenerator;

    ConceptGovernanceWriter(ConceptBuildContext context) {
        this.ontModel = context.ontModel();
        this.uriGenerator = context.uriGenerator();
    }

    void addSharedGovernanceMetadata(Resource resource, String agendaCode, String agendaSystemCode,
                                              List<String> sharingMethod, String acquisitionMethod, String contentType,
                                              List<String> privacyProvisions) {
        if (agendaCode != null && !agendaCode.trim().isEmpty()) {
            addAgenda(resource, agendaCode);
        }
        if (agendaSystemCode != null && !agendaSystemCode.trim().isEmpty()) {
            addAIS(resource, agendaSystemCode);
        }

        addSharingMethodMetadata(sharingMethod, resource);

        if (acquisitionMethod != null && !acquisitionMethod.trim().isEmpty()) {
            addGovernanceProperty(resource, acquisitionMethod, ZPUSOB_ZISKANI);
        }
        if (contentType != null && !contentType.trim().isEmpty()) {
            addGovernanceProperty(resource, contentType, TYP_OBSAHU);
        }

        addPrivacyProvisionsMetadata(resource, privacyProvisions);
    }

    void addSharingMethodMetadata(List<String> sharingMethod, Resource resource) {
        if (sharingMethod != null && !sharingMethod.isEmpty()) {
            for (String method : sharingMethod) {
                if (method != null && !method.trim().isEmpty()) {
                    addGovernanceProperty(resource, method, ZPUSOB_SDILENI);
                }
            }
        }
    }
    void addPrivacyProvisionsMetadata(Resource resource, List<String> privacyProvisions) {
        if (privacyProvisions != null && !privacyProvisions.isEmpty()) {
            for (String provision : privacyProvisions) {
                if (provision == null || provision.trim().isEmpty()) continue;
                String canonical = EsbirkaEliParser.canonicalizeHost(provision.trim());
                if (!SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                    log.warn("Skipping privacy provision — not a canonical e-Sbírka ELI IRI: {}", provision.trim());
                    continue;
                }
                Property provisionProperty = ontModel.createProperty(
                        uriGenerator.getEffectiveNamespace() + USTANOVENI_NEVEREJNOST);
                resource.addProperty(provisionProperty, ontModel.createResource(canonical));
            }
        }
    }

    void addAgenda(Resource resource, String agendaCode) {
        if (UtilityMethods.isValidAgendaValue(agendaCode)) {
            String transformedAgenda = UtilityMethods.transformAgendaValue(agendaCode);
            Property agendaProperty = ontModel.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);
            resource.addProperty(agendaProperty, ontModel.createResource(transformedAgenda));
        }
    }

    void addAIS(Resource resource, String aisCode) {
        if (UtilityMethods.isValidAISValue(aisCode)) {
            String transformedAIS = UtilityMethods.transformAISValue(aisCode);
            Property aisProperty = ontModel.createProperty(DEFAULT_NS + AGENDOVY_104 + UDAJE_AIS);
            resource.addProperty(aisProperty, ontModel.createResource(transformedAIS));
        }
    }

    void addGovernanceProperty(Resource resource, String value, String propertyName) {
        String sanitizedValue = UtilityMethods.sanitizeForIRI(value);
        String governanceIRI = switch (propertyName) {
            case TYP_OBSAHU -> "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/" + sanitizedValue;
            case ZPUSOB_SDILENI ->
                    "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/" + sanitizedValue;
            case ZPUSOB_ZISKANI ->
                    "https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/" + sanitizedValue;
            default -> null;
        };

        if (governanceIRI != null) {
            Property property = ontModel.createProperty(OFN_NAMESPACE + propertyName);
            resource.addProperty(property, ontModel.createResource(governanceIRI));
        }
    }

    void addIsInPPDF(Resource resource, Boolean isInPPDF) {
        if (isInPPDF != null) {
            Property ppdfProperty = ontModel.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
            resource.addLiteral(ppdfProperty, ontModel.createTypedLiteral(isInPPDF));
        }
    }
}
