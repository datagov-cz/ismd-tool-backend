package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberSearchModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;

import java.util.List;
import java.util.Optional;

/**
 * e-Sbírka data source behind {@code /api/eli/*}. {@code esbirka.provider} picks the
 * implementation: {@code sparql} ({@link EsbirkaSparqlClient}) or {@code rest} (the REST API).
 *
 * <p>Both return IRIs in the canonical form {@code https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/...}.
 * A missing law, version or fragment is an empty result, never an exception; an unreachable
 * source is {@link com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException}.
 */
public interface EsbirkaClient {

    /** Label for {@link com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException}. */
    String ESBIRKA_LABEL = "e-Sbírka";

    List<LawModel> searchLaws(String q, int limit);

    /**
     * Acts whose číslo starts with {@code cisloPrefix}, optionally narrowed to years starting with
     * {@code rokPrefix}, grouped by číslo. Returns at least the {@code groupLimit} best groups when
     * that many exist; callers sort and cap.
     */
    LawNumberSearchModel searchLawsByNumber(String cisloPrefix, String rokPrefix, int groupLimit);

    /** @param sbirka collection code from the ELI ({@code sb}, {@code sm}, …) */
    Optional<LawModel> findLaw(String sbirka, String number, int year);

    /** Versions of a law, newest first by účinnost od; exactly one flagged latest when any exist. */
    List<LawVersionModel> fetchVersions(String lawIri);

    List<FragmentModel> fetchFragments(String versionIri);

    /** Every fragment of a version in document order, each with its own rendered body. */
    List<FragmentModel> fetchVersionContent(String versionIri);

    Optional<FragmentResolutionModel> resolveFragment(String fragmentIri, String versionIri, String lawIri);
}
