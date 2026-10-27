package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.models.eli.LawNumberSearchModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.LAW_49_1997;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.law;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Name mode of {@code searchLawsGrouped}: which queries reach it, and the result shape. */
@ExtendWith(MockitoExtension.class)
class EsbirkaNameSearchResultTest {

    @Mock
    private EsbirkaClient client;

    private EsbirkaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = EsbirkaServiceWiring.build(client, null, null);
    }

    @Test
    void aQueryStartingWithALetterSearchesNamesOneGroupPerLawInRankOrder() {
        when(client.searchLawsByName("civilním letectví", 20)).thenReturn(List.of(
                law("49", 1997, "Zákon o civilním letectví"),
                law("370", 2025, "Zákon, kterým se mění zákon č. 49/1997 Sb.")));

        LawSearchResultDto out = service.searchLawsGrouped("  civilním letectví ", 20);

        assertThat(out.getQuery()).isEqualTo("civilním letectví");
        assertThat(out.getGroups().stream().map(g -> g.getCislo()).toList()).isEqualTo(List.of("49", "370"));
        assertThat(out.getGroups().stream().allMatch(g -> g.getCount() == 1 && !g.isExactNumberMatch())).isTrue();
        LawDto top = out.getGroups().get(0).getLaws().get(0);
        assertThat(top.getIri()).isEqualTo(LAW_49_1997);
        assertThat(top.getDisplayName()).isEqualTo("Zákon č. 49/1997 Sb., o civilním letectví");
        assertThat(top.getCitace()).isEqualTo("49/1997 Sb.");
        assertThat(out.isAmbiguous()).isTrue();
        assertThat(out.getTotalMatches()).isEqualTo(2);
        assertThat(out.isTruncated()).isFalse();
        verify(client, never()).searchLawsByNumber(any(), any(), anyInt());
    }

    @Test
    void aFullPageOfResultsIsReportedAsTruncated() {
        when(client.searchLawsByName("letectví", 1)).thenReturn(List.of(
                law("49", 1997, "Zákon o civilním letectví")));

        LawSearchResultDto out = service.searchLawsGrouped("letectví", 1);

        assertThat(out.isTruncated()).isTrue();
        assertThat(out.isAmbiguous()).as("one act: nothing left to choose").isFalse();
    }

    @Test
    void fewerThanThreeLettersSearchesNothing() {
        LawSearchResultDto out = service.searchLawsGrouped("ob", 20);

        assertThat(out.getGroups()).isEmpty();
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
}