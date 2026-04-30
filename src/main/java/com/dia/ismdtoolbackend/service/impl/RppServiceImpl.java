package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.RppSearchResultDto;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.models.rpp.RppSnapshot;
import com.dia.ismdtoolbackend.service.RppService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.utility.DataTypeConverter;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RppServiceImpl implements RppService {

    private final RppSnapshotHolder snapshotHolder;

    @Override
    public List<RppSearchResultDto> searchAgendas(String q, int limit) {
        RppSnapshot snap = snapshotHolder.get();
        if (snap.isEmpty()) return List.of();
        String needle = isBlank(q) ? null : normalize(q);
        return snap.getAgendas().stream()
                .filter(a -> needle == null || matches(a.getCode(), a.getNazev(), needle))
                .limit(limit)
                .map(a -> new RppSearchResultDto(a.getIri(), a.getCode(), a.getNazev()))
                .toList();
    }

    @Override
    public List<RppSearchResultDto> searchIsvs(String q, int limit, String preferredAgendaCode) {
        RppSnapshot snap = snapshotHolder.get();
        if (snap.isEmpty()) return List.of();
        String needle = isBlank(q) ? null : normalize(q);
        String preferredIri = resolvePreferredAgendaIri(preferredAgendaCode);

        List<RppIsvs> filtered = snap.getIsvs().stream()
                .filter(i -> needle == null || matches(i.getCode(), i.getNazev(), needle))
                .collect(Collectors.toCollection(ArrayList::new));

        if (preferredIri != null) {
            final String pref = preferredIri;
            filtered.sort(Comparator.comparingInt(
                    i -> i.getAgendaIris().contains(pref) ? 0 : 1));
        }

        return filtered.stream().limit(limit)
                .map(i -> new RppSearchResultDto(i.getIri(), i.getCode(), i.getNazev()))
                .toList();
    }

    private String resolvePreferredAgendaIri(String code) {
        if (isBlank(code)) return null;
        if (!UtilityMethods.isValidAgendaValue(code)) {
            log.debug("preferredAgendaCode rejected by isValidAgendaValue: {}", code);
            return null;
        }
        String transformed = UtilityMethods.transformAgendaValue(code);
        if (!DataTypeConverter.isUri(transformed)) {
            log.debug("transformAgendaValue produced non-URI for code={}: {}", code, transformed);
            return null;
        }
        return transformed;
    }

    private static boolean matches(String code, String nazev, String needle) {
        return (code != null && normalize(code).contains(needle))
                || (nazev != null && normalize(nazev).contains(needle));
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
