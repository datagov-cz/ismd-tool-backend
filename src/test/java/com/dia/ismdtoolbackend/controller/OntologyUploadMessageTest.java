package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OntologyUploadMessageTest {

    private static OntologyMetadataModel uploaded() {
        OntologyMetadataModel model = new OntologyMetadataModel();
        model.setGraphName("https://slovník.gov.cz/agendový/999");
        return model;
    }

    @Test
    void fullImportReadsAsBefore() {
        OntologyMetadataModel model = uploaded();
        model.setRejectedConcepts(List.of());
        model.setCorrectedIris(List.of());

        assertEquals("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999",
                OntologyController.uploadMessage(model));
        assertEquals("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999",
                OntologyController.uploadMessage(uploaded()));
    }

    @Test
    void partialImportNamesWhatWasLeftOutAndCorrected() {
        OntologyMetadataModel model = uploaded();
        model.setRejectedConcepts(List.of(new RejectedConceptDto("a", "x"), new RejectedConceptDto("b", "y")));
        model.setCorrectedIris(List.of("c/"));

        assertEquals("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999"
                        + ". Neimportované pojmy: 2. IRI opravená o koncové lomítko: 1",
                OntologyController.uploadMessage(model));
    }
}
