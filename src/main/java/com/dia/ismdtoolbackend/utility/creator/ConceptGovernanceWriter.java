package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;

import java.util.List;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Writes agenda, AIS, sharing, acquisition and content type, and the PPDF flag. */
final class ConceptGovernanceWriter {

    private final OntModel ontModel;

    ConceptGovernanceWriter(ConceptBuildContext context) {
        this.ontModel = context.ontModel();
    }

    void addSharedGovernanceMetadata(Resource resource, String agendaCode, String agendaSystemCode,
                                              List<String> sharingMethod, String acquisitionMethod, String contentType) {
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
            resource.addLiteral(ontModel.createProperty(OfnIris.JE_SDILEN_V_PPDF), ontModel.createTypedLiteral(isInPPDF));
        }
    }
}
