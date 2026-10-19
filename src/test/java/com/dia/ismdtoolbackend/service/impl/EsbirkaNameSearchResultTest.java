package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberSearchModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Name mode of {@code searchLawsGrouped}: which queries reach it, and the result shape. */
@ExtendWith(MockitoExtension.class)
class EsbirkaNameSearchResultTest {

    private static final String B = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz";

    @Mock
    private EsbirkaClient client;

    private EsbirkaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EsbirkaServiceImpl(client, null, null, null);
    }

    @Test
    void aQueryStartingWithALetterSearchesNamesOneGroupPerLawInRankOrder() {
        when(client.searchLawsByName("civilním letectví", 20)).thenReturn(List.of(
                law("/sb/1997/49", "49/1997 Sb.", "49", 1997, "Zákon o civilním letectví"),
                law("/sb/2025/370", "370/2025 Sb.", "370", 2025, "Zákon, kterým se mění zákon č. 49/1997 Sb.")));

        LawSearchResultDto out = service.searchLawsGrouped("  civilním letectví ", 20);

        assertEquals("civilním letectví", out.getQuery());
        assertEquals(List.of("49", "370"), out.getGroups().stream().map(g -> g.getCislo()).toList());
        assertTrue(out.getGroups().stream().allMatch(g -> g.getCount() == 1 && !g.isExactNumberMatch()));
        LawDto top = out.getGroups().get(0).getLaws().get(0);
        assertEquals(B + "/sb/1997/49", top.getIri());
        assertEquals("Zákon č. 49/1997 Sb., o civilním letectví", top.getDisplayName());
        assertEquals("49/1997 Sb.", top.getCitace());
        assertTrue(out.isAmbiguous());
        assertEquals(2, out.getTotalMatches());
        assertFalse(out.isTruncated());
        verify(client, never()).searchLawsByNumber(any(), any(), anyInt());
    }

    @Test
    void aFullPageOfResultsIsReportedAsTruncated() {
        when(client.searchLawsByName("letectví", 1)).thenReturn(List.of(
                law("/sb/1997/49", "49/1997 Sb.", "49", 1997, "Zákon o civilním letectví")));

        LawSearchResultDto out = service.searchLawsGrouped("letectví", 1);

        assertTrue(out.isTruncated());
        assertFalse(out.isAmbiguous(), "one act: nothing left to choose");
    }

    @Test
    void fewerThanThreeLettersSearchesNothing() {
        LawSearchResultDto out = service.searchLawsGrouped("ob", 20);

        assertTrue(out.getGroups().isEmpty());
        verify(client, never()).searchLawsByName(anyString(), anyInt());
        verify(client, never()).searchLawsByNumber(any(), any(), anyInt());
    }

    @Test
    void threeLettersIsEnough() {
        when(client.searchLawsByName("obč", 20)).thenReturn(List.of());

        service.searchLawsGrouped("obč", 20);

        verify(client).searchLawsByName("obč", 20);
    }

    @Test
    void theWholeNameReachesTheClientEvenWhenItContainsSb() {
        // The number-mode needle split cuts at the first " Sb" — "zákon o Sbírce" must not become "zákon o".
        when(client.searchLawsByName("zákon o Sbírce zákonů", 20)).thenReturn(List.of());

        service.searchLawsGrouped("zákon o Sbírce zákonů", 20);

        verify(client).searchLawsByName("zákon o Sbírce zákonů", 20);
    }

    @Test
    void aQueryStartingWithADigitStaysInNumberMode() {
        when(client.searchLawsByNumber("12", "2020", 20)).thenReturn(LawNumberSearchModel.empty());
        when(client.searchLawsByNumber("49 letectví", null, 20)).thenReturn(LawNumberSearchModel.empty());

        service.searchLawsGrouped("12/2020 Sb. m. s.", 20);
        service.searchLawsGrouped("49 letectví", 20);

        verify(client, never()).searchLawsByName(anyString(), anyInt());
    }

    private static LawModel law(String staleUrl, String citace, String cislo, int rok, String nazev) {
        return new LawModel(B + staleUrl, citace, cislo, rok, "sb", nazev);
    }
}