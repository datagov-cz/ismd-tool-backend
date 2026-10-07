package com.dia.ismdtoolbackend.models.nkod;

import java.util.List;

/**
 * A codelist plus the distribution files its codelist IRI is read from. Held in the
 * snapshot only; the wire shape is {@link NkodCodelist}, served for resolved entries only.
 *
 * @param codelist     the catalogue metadata; {@code codeListIri} is null while unresolved
 * @param downloadUrls current {@code .jsonld} download URLs, sorted; empty when none is published
 */
public record NkodCodelistEntry(NkodCodelist codelist, List<String> downloadUrls) {

    public NkodCodelistEntry {
        downloadUrls = downloadUrls == null ? List.of() : List.copyOf(downloadUrls);
    }

    public String datasetIri() {
        return codelist.getDatasetIri();
    }

    public String codeListIri() {
        return codelist.getCodeListIri();
    }

    public boolean isResolved() {
        return codelist.getCodeListIri() != null;
    }

    /** A copy with the given codelist IRI; null marks it unresolved. Leaves this entry untouched. */
    public NkodCodelistEntry withCodeListIri(String codeListIri) {
        return new NkodCodelistEntry(codelist.toBuilder().codeListIri(codeListIri).build(), downloadUrls);
    }
}
