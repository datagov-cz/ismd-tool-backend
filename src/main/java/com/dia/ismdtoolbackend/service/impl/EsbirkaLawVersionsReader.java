package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.dia.ismdtoolbackend.service.impl.EsbirkaLawReference.requireValidIri;

/** Reads the versions (znění) of a law, cached per canonical law IRI. */
@Component
@RequiredArgsConstructor
@Slf4j
class EsbirkaLawVersionsReader {

    private final EsbirkaClient client;

    /**
     * Rewrite a legacy e-Sbírka host to the canonical one and strip trailing slashes, leaving
     * everything else untouched.
     *
     * <p>Every e-Sbírka IRI entering this service passes through here before it is validated,
     * compared or cached, so all endpoints accept the same host spellings that {@code /resolve}
     * and the concept write paths already accept. Called reflectively by the {@code @Cacheable}
     * SpEL keys below, so it must stay {@code public}.
     */
    public String canonicalizeEsbirkaIri(String iri) {
        return EsbirkaLawReference.canonicalize(iri);
    }

    // Keyed on the canonical form so a legacy-host IRI shares the entry with its canonical
    // twin rather than issuing an identical second query under its own key.
    @Cacheable(cacheNames = "esbirkaLawVersions",
            key = "#root.target.canonicalizeEsbirkaIri(#lawIri)",
            sync = true)
    public List<LawVersionDto> getVersions(String lawIri) {
        String iri = requireValidIri(lawIri, "Neplatný identifikátor právního aktu.");
        List<LawVersionModel> rows = client.fetchVersions(iri);
        List<LawVersionDto> out = new ArrayList<>(rows.size());
        for (LawVersionModel m : rows) {
            out.add(toVersionDto(m));
        }
        return out;
    }

    private static LawVersionDto toVersionDto(LawVersionModel m) {
        String eliPath = SparqlIriValidator.extractEsbirkaEliPath(m.getIri());
        return new LawVersionDto(
                m.getIri(),
                eliPath,
                m.getUcinnostOd(),
                m.getUcinnostDo(),
                m.getVersionType(),
                m.isLatest()
        );
    }
}
