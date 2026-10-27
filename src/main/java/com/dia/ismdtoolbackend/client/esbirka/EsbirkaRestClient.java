package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.exception.EsbirkaDocumentNotFoundException;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberSearchModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link EsbirkaClient} on the e-Sbírka REST API ({@code esbirka.provider=rest}). Every call
 * draws from the current {@code /api/eli} request's deadline. A missing document is an empty
 * result, as with SPARQL, so the service behaves the same on either provider.
 */
@Component
@ConditionalOnProperty(name = "esbirka.provider", havingValue = "rest")
class EsbirkaRestClient implements EsbirkaClient {

    private final EsbirkaRestTransport transport;
    private final EsbirkaRestReader reader;
    private final EsbirkaRestNameSearch nameSearch;

    EsbirkaRestClient(EsbirkaRestTransport transport, EsbirkaRestReader reader, EsbirkaRestNameSearch nameSearch) {
        this.transport = transport;
        this.reader = reader;
        this.nameSearch = nameSearch;
    }

    @Override
    public LawNumberSearchModel searchLawsByNumber(String cisloPrefix, String rokPrefix, int groupLimit) {
        if (!EsbirkaRestNumberSearch.isSearchable(cisloPrefix, rokPrefix)) {
            return LawNumberSearchModel.empty();
        }
        EsbirkaRestDtos.SearchResult result = transport.post(transport.uri("/rozsirena-vyhledavani"),
                EsbirkaRestNumberSearch.request(cisloPrefix, rokPrefix), EsbirkaRestDtos.SearchResult.class, deadline());
        return EsbirkaRestNumberSearch.group(result, cisloPrefix);
    }

    @Override
    public List<LawModel> searchLawsByName(String q, int limit) {
        return nameSearch.search(q, limit, deadline());
    }

    @Override
    public Optional<LawModel> findLaw(String sbirka, String number, int year) {
        String lawStaleUrl = "/" + sbirka + "/" + year + "/" + number;
        return orEmpty(() -> Optional.of(EsbirkaRestMapper.toLaw(transport.get("detail",
                transport.uri("/dokumenty-sbirky/" + StaleUrls.encodePathSegment(lawStaleUrl)),
                EsbirkaRestDtos.DocumentDetail.class, deadline()))), Optional.empty());
    }

    @Override
    public List<LawVersionModel> fetchVersions(String lawIri) {
        return orEmpty(() -> reader.versions(StaleUrls.lawStaleUrl(lawIri), deadline()), List.of());
    }

    @Override
    public List<FragmentModel> fetchVersionContent(String versionIri) {
        if (!StaleUrls.hasVersion(versionIri)) {
            return List.of();
        }
        return orEmpty(() -> reader.versionContent(StaleUrls.versionStaleUrl(versionIri), deadline()), List.of());
    }

    /**
     * Read from the whole cached version — the API has no single-fragment text endpoint — so the
     * first resolve of a version loads it, and later resolves and content loads reuse it.
     */
    @Override
    public Optional<FragmentResolutionModel> resolveFragment(String fragmentIri, String versionIri, String lawIri) {
        Optional<FragmentModel> fragment = fetchVersionContent(versionIri).stream()
                .filter(f -> fragmentIri.equals(f.getIri()))
                .findFirst();
        if (fragment.isEmpty()) {
            return Optional.empty();
        }
        Optional<LawVersionModel> version = fetchVersions(lawIri).stream()
                .filter(v -> versionIri.equals(v.getIri()))
                .findFirst();
        return Optional.of(new FragmentResolutionModel(fragment.get().getCitation(),
                version.map(LawVersionModel::getUcinnostDo).orElse(null),
                version.map(LawVersionModel::isLatest).orElse(false),
                fragment.get().getBodyHtml()));
    }

    private EsbirkaDeadline deadline() {
        return EsbirkaRequestDeadline.current().orElseGet(transport::newDeadline);
    }

    private static <T> T orEmpty(Supplier<T> read, T empty) {
        try {
            return read.get();
        } catch (EsbirkaDocumentNotFoundException e) {
            return empty;
        }
    }
}
