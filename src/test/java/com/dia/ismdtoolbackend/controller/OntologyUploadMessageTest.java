package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyUploadResultDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyUploadMessageTest {

    private static OntologyUploadResultDto uploaded(List<RejectedConceptDto> rejected, List<String> corrected) {
        OntologySummaryDto ontology = new OntologySummaryDto();
        ontology.setGraphName("https://slovník.gov.cz/agendový/999");
        return new OntologyUploadResultDto(ontology, rejected, corrected);
    }

    @Test
    void fullImportReadsAsBefore() {
        assertThat(OntologyController.uploadMessage(uploaded(List.of(), List.of())))
                .isEqualTo("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999");
        assertThat(OntologyController.uploadMessage(uploaded(null, null)))
                .isEqualTo("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999");
    }

    @Test
    void partialImportNamesWhatWasLeftOutAndCorrected() {
        OntologyUploadResultDto result = uploaded(
                List.of(new RejectedConceptDto("a", "x"), new RejectedConceptDto("b", "y")), List.of("c/"));

        assertThat(OntologyController.uploadMessage(result))
                .isEqualTo("Slovník úspěšně nahrán: https://slovník.gov.cz/agendový/999"
                        + ". Neimportované pojmy: 2. IRI opravená o koncové lomítko: 1");
    }
}
