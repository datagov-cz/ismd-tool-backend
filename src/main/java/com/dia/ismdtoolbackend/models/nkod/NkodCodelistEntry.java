package com.dia.ismdtoolbackend.models.nkod;

import java.util.List;

/**
 * A codelist plus the distribution files its codelist IRI is read from. Held in the
 * snapshot only; the wire shape is {@link NkodCodelist}.
 *
 * @param codelist     the codelist as served to the FE
 * @param downloadUrls current {@code .jsonld} download URLs, sorted; empty when none is published
 */
public record NkodCodelistEntry(NkodCodelist codelist, List<String> downloadUrls) {

    public NkodCodelistEntry {
        downloadUrls = downloadUrls == null ? List.of() : List.copyOf(downloadUrls);
    }

    public String datasetIri() {
        return codelist.getDatasetIri();
    }
}
