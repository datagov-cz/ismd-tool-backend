package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.client.NkodSparqlClient;
import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.controller.dto.GetNkodDatasetDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDatasetListItemDto;
import com.dia.ismdtoolbackend.controller.dto.NkodDistributionDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetDetail;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetRow;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetSnapshot;
import com.dia.ismdtoolbackend.models.nkod.NkodDistribution;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptResolutionEngine;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.sparql.SparqlSolutions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NkodDatasetServiceImpl implements NkodDatasetService {

    private static final String DEFAULT_LANG = "cs";

    /**
     * Czech collation, so {@code č} sorts after {@code c} rather than at the end of the
     * alphabet. The list endpoint instead sorts on the snapshot's diacritic-folded key, which
     * it needs anyway for accent-insensitive search; nothing is searched here.
     */
    private static final Comparator<String> CZECH_TITLE_ORDER =
            Collator.getInstance(Locale.forLanguageTag("cs"))::compare;

    private final NkodDatasetSnapshotHolder snapshotHolder;
    private final NkodSparqlClient client;
    private final ReferencedConceptResolutionEngine resolutionEngine;
    private final NkodConfig config;

    /**
     * Served from the in-memory snapshot rather than the endpoint.
     */
    @Override
    public NkodDatasetListDto listDatasets(String query, int limit, int offset, String lang) {
        int safeLimit = clampLimit(limit);
        int safeOffset = Math.max(offset, 0);

        NkodDatasetSnapshot snapshot = snapshotHolder.get();
        List<NkodDatasetRow> matches = snapshot.search(query, safeLang(lang));

        List<NkodDatasetListItemDto> page = matches.stream()
                .skip(safeOffset)
                .limit(safeLimit)
                .map(this::toListItem)
                .toList();

        return new NkodDatasetListDto(page, matches.size());
    }

    @Override
    public GetNkodDatasetDto getDatasetDetail(String iri) {
        if (iri == null || iri.isBlank()) {
            throw new IllegalArgumentException("IRI datové sady je povinné.");
        }

        NkodDatasetDetail detail = client.fetchDatasetDetail(iri)
                .orElseThrow(() -> new NkdResourceNotFoundException(
                        "Datová sada nebyla v NKOD nalezena: " + iri));

        List<MinimalConceptDto> concepts = resolveConcepts(detail.conceptIris());

        return GetNkodDatasetDto.builder()
                .iri(detail.iri())
                .name(detail.name())
                .description(detail.description())
                .concepts(concepts)
                .conceptCount(concepts.size())
                .distributions(toDistributions(detail.distributions()))
                .build();
    }

    /**
     * Queried live, not from the snapshot: the snapshot indexes titles and descriptions only,
     * and carries no {@code týká-se-pojmu} annotations to filter on.
     *
     * <p>Not paged. A concept is annotated by a handful of datasets, so the whole result is
     * returned and {@code totalCount} equals its size; the shared list envelope is reused.
     */
    @Override
    public NkodDatasetListDto listDatasetsByConcept(String conceptIri) {
        if (conceptIri == null || conceptIri.isBlank()) {
            throw new IllegalArgumentException("IRI pojmu je povinné.");
        }
        if (!SparqlIriValidator.isSafeHttpIri(SparqlSolutions.toRawUtf8(conceptIri))) {
            throw new IllegalArgumentException("Neplatné IRI pojmu: " + conceptIri);
        }

        List<NkodDatasetListItemDto> datasets = client.fetchDatasetsByConcept(conceptIri).stream()
                .sorted(Comparator
                        .comparing((NkodDatasetRow row) ->
                                        NkodDatasetSnapshot.preferred(row.name(), DEFAULT_LANG),
                                CZECH_TITLE_ORDER)
                        .thenComparing(NkodDatasetRow::iri))
                .map(this::toListItem)
                .toList();

        return new NkodDatasetListDto(datasets, datasets.size());
    }

    /**
     * Drops distributions with no usable link: the publisher supplied neither
     * {@code downloadURL} nor {@code accessURL}, so there is nothing for the FE to render.
     */
    private List<NkodDistributionDto> toDistributions(List<NkodDistribution> distributions) {
        return distributions.stream()
                .filter(d -> d.link() != null && !d.link().isBlank())
                .map(d -> NkodDistributionDto.builder()
                        .iri(d.iri())
                        .name(d.name())
                        .link(d.link())
                        .format(d.format())
                        .mediaType(d.mediaType())
                        .sluzba(d.isService())
                        .build())
                .toList();
    }

    /**
     * Resolves concept IRIs to names via the shared engine, pinned to {@link SearchSource#NKD}
     * — these are published NKD concepts, and the NKD-only cache namespace keeps them from
     * colliding with a local working copy at the same IRI.
     */
    private List<MinimalConceptDto> resolveConcepts(List<String> conceptIris) {
        if (conceptIris.isEmpty()) {
            return List.of();
        }
        Map<String, ResolvedConceptDto> resolved =
                resolutionEngine.resolveAll(conceptIris, SearchSource.NKD);

        return conceptIris.stream()
                .map(iri -> {
                    ResolvedConceptDto hit = resolved.get(iri);
                    return MinimalConceptDto.builder()
                            .iri(iri)
                            .slug(hit == null ? null : hit.conceptSlug())
                            .name(hit == null ? null : hit.conceptName())
                            .build();
                })
                .toList();
    }

    private NkodDatasetListItemDto toListItem(NkodDatasetRow row) {
        return NkodDatasetListItemDto.builder()
                .iri(row.iri())
                .name(row.name())
                .description(row.description())
                .build();
    }

    private static String safeLang(String lang) {
        return (lang == null || lang.isBlank()) ? DEFAULT_LANG : lang;
    }

    private int clampLimit(int limit) {
        int max = config.getSearch().getMaxLimit();
        if (limit <= 0) {
            return config.getSearch().getDefaultLimit();
        }
        return Math.min(limit, max);
    }
}