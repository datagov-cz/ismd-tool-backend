package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.controller.dto.GetNkodDatasetDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListDto;

/** Reads NKOD datasets and the concepts they are annotated with (issue #123). */
public interface NkodDatasetService {

    /**
     * One page of datasets, filtered by {@code query} when given.
     *
     * @param query  free text over title and description; diacritic-insensitive, may be blank
     * @param limit  page size, clamped to the configured maximum
     * @param offset rows to skip
     * @param lang   preferred language for sorting and display
     */
    NkodDatasetListDto listDatasets(String query, int limit, int offset, String lang);

    /**
     * Detail of one dataset, with its {@code týká-se-pojmu} concepts resolved to names.
     *
     * @throws com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException
     *         when the dataset is not in the catalogue
     */
    GetNkodDatasetDto getDatasetDetail(String iri);
}