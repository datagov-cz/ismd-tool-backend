package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(OntologyController.uploadMessage(model))
                .isEqualTo("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999");
        assertThat(OntologyController.uploadMessage(uploaded()))
                .isEqualTo("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999");
    }

    @Test
    void partialImportNamesWhatWasLeftOutAndCorrected() {
        OntologyMetadataModel model = uploaded();
        model.setRejectedConcepts(List.of(new RejectedConceptDto("a", "x"), new RejectedConceptDto("b", "y")));
        model.setCorrectedIris(List.of("c/"));

        assertThat(OntologyController.uploadMessage(model))
                .isEqualTo("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999"
                        + ". Neimportované pojmy: 2. IRI opravená o koncové lomítko: 1");
    }
}
