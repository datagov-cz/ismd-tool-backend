package com.dia.ismdtoolbackend.models.eli;

import java.time.LocalDate;

public record FragmentResolutionModel(
        String citation,
        LocalDate versionValidUntil,
        boolean isLatest,
        String bodyHtml
) {
}
