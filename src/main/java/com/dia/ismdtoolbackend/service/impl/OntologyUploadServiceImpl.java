package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.OntologyMetadata;
import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OntologyUploadServiceImpl implements OntologyUploadService {

    private final Dataset jenaDataset;
    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OntologyMetadataRepository ontologyMetadataRepository;

    @Override
    public Lang determineRDFFormat(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName != null) {
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

            switch (extension) {
                case "ttl":
                case "turtle":
                    return Lang.TURTLE;
                case "jsonld":
                case "json-ld":
                    return Lang.JSONLD;
                default:
                    break;
            }
        }

        String contentType = file.getContentType();
        if (contentType != null) {
            if (contentType.contains("turtle")) {
                return Lang.TURTLE;
            } else if (contentType.contains("json")) {
                return Lang.JSONLD;
            }
        }

        return null;
    }

    @Override
    public OntologyMetadataDto uploadFromFile(MultipartFile file, String graphName, Lang rdfLang) throws IOException {
        Model uploadedModel = ModelFactory.createDefaultModel();

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(file.getBytes())) {
            RDFDataMgr.read(uploadedModel, inputStream, rdfLang);
        }

        log.info("Loaded model has {} statements", uploadedModel.size());
        log.info("Writing to graph: {}", graphName);

        final String finalGraphName = graphName;
        jenaDataset.executeWrite(() -> {
            Model namedModel = jenaDataset.getNamedModel(finalGraphName);
            namedModel.removeAll();
            namedModel.add(uploadedModel);
            log.info("After write: graph {} has {} statements", finalGraphName, namedModel.size());
        });

        OntologyMetadataDto ontologyMetadataDto = new OntologyMetadataDto();
        ontologyMetadataDto.setGraphName(finalGraphName);
        ontologyMetadataDto.setUserId("test_user");
        OntologyMetadata ontologyMetadata = ontologyMetadataMapper.toEntity(ontologyMetadataDto);
        OntologyMetadata savedOntologyMetadata = ontologyMetadataRepository.save(ontologyMetadata);
        return ontologyMetadataMapper.toDto(savedOntologyMetadata);
    }
}
