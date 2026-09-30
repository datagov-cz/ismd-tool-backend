package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.controller.dto.CodeListDto;
import com.dia.ismdtoolbackend.controller.dto.NkodCodelistCheckDto;
import com.dia.ismdtoolbackend.enums.NkodCodelistStatus;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistEntry;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistSnapshot;
import com.dia.ismdtoolbackend.utility.sparql.SparqlSolutions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** The NKOD codelist picker, and the check of a concept's stored codelist against it. */
@Service
@RequiredArgsConstructor
public class NkodCodelistService {

    private final NkodCodelistSnapshotHolder holder;

    /** Codelists that have a codelist IRI, in title order; 503 when the snapshot never loaded. */
    public List<NkodCodelist> list() {
        return holder.get().getEntries().stream()
                .filter(NkodCodelistEntry::isResolved)
                .map(NkodCodelistEntry::codelist)
                .toList();
    }

    /**
     * Compares a stored codelist with the snapshot, keyed by its dataset IRI. Empty — nothing to
     * report — when the snapshot hasn't loaded or the dataset has no readable codelist IRI;
     * {@code MISSING} only when a loaded snapshot doesn't list the dataset. Never fetches.
     */
    public Optional<NkodCodelistCheckDto> check(CodeListDto stored) {
        if (stored == null || isBlank(stored.getDatovaSadaVNkod())) {
            return Optional.empty();
        }
        NkodCodelistSnapshot snapshot = holder.peek();
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        Optional<NkodCodelistEntry> entry = snapshot.find(stored.getDatovaSadaVNkod());
        if (entry.isEmpty()) {
            return Optional.of(NkodCodelistCheckDto.builder().status(NkodCodelistStatus.MISSING).build());
        }
        if (!entry.get().isResolved()) {
            return Optional.empty();
        }
        NkodCodelistStatus status = sameIri(stored.getIri(), entry.get().codeListIri())
                ? NkodCodelistStatus.CURRENT
                : NkodCodelistStatus.NEW_VERSION;
        return Optional.of(NkodCodelistCheckDto.builder()
                .status(status)
                .codelist(entry.get().codelist())
                .build());
    }

    private static boolean sameIri(String stored, String current) {
        if (stored == null) {
            return false;
        }
        return SparqlSolutions.toRawUtf8(stored.strip()).equals(SparqlSolutions.toRawUtf8(current.strip()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
