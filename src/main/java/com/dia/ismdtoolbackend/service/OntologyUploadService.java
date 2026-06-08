package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import org.apache.jena.riot.Lang;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface OntologyUploadService {
    Lang determineRDFFormat(MultipartFile file);

    /**
     * Uploads a vocabulary. If owned concepts lack {@code skos:inScheme} and
     * {@code normalizeMode} is null, throws
     * {@link com.dia.ismdtoolbackend.exception.InSchemeDecisionRequiredException}
     * (nothing persisted) so the user can choose how to handle them on re-upload.
     *
     * @param normalizeMode        how to treat missing-inScheme concepts (null = require a decision)
     * @param conceptsToNormalize  for {@link NormalizeMode#PER_CONCEPT}, the IRIs to normalize
     */
    OntologyMetadataModel uploadFromFile(MultipartFile file, String userId,
                                         NormalizeMode normalizeMode,
                                         List<String> conceptsToNormalize) throws IOException, OntologyUploadException;
}
