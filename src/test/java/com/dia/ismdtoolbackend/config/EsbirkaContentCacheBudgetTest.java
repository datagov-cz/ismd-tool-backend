package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.CacheManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsbirkaContentCacheBudgetTest {

    private final EsbirkaContentWeigher weigher = new EsbirkaContentWeigher();

    @Test
    void versionContentWeighsTwoBytesPerCharacterPlusOverhead() {
        String body = "x".repeat(10_000);
        List<FragmentModel> content = List.of(new FragmentModel("i", null, null, "par", "000000", body));

        int weight = weigher.weigh("/sb/1997/49/2025-11-01", content);

        assertTrue(weight >= 20_000 && weight < 21_000, "weight " + weight);
    }

    @Test
    void renderedContentCountsTheTreeAndTheWholeBody() {
        FragmentDto child = new FragmentDto();
        child.setBodyHtml("y".repeat(5_000));
        FragmentDto root = new FragmentDto();
        root.setChildren(new ArrayList<>(List.of(child)));
        LawContentDto content = LawContentDto.builder().bodyHtml("z".repeat(5_000)).fragments(List.of(root)).build();

        assertTrue(weigher.weigh("49/1997@", content) >= 20_000);
    }

    @Test
    void lawAndVersionContentShareOneBudget() {
        EsbirkaConfig esbirka = new EsbirkaConfig();
        esbirka.setCacheBudgetMb(1);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("esbirkaConfig", esbirka);
        CacheManager manager = new CacheConfig().cacheManager(beans.getBeanProvider(EsbirkaConfig.class));

        @SuppressWarnings("unchecked")
        Cache<Object, Object> lawContent = (Cache<Object, Object>) manager.getCache("esbirkaLawContent").getNativeCache();
        assertSame(lawContent, manager.getCache("esbirkaVersionContent").getNativeCache());

        List<FragmentModel> fourHundredKb = List.of(new FragmentModel("i", null, null, "par", "0", "x".repeat(200_000)));
        manager.getCache("esbirkaVersionContent").put("/sb/1/1/2020-01-01", fourHundredKb);
        manager.getCache("esbirkaLawContent").put("1/1@", LawContentDto.builder().bodyHtml("z".repeat(200_000)).build());
        manager.getCache("esbirkaVersionContent").put("/sb/2/2/2020-01-01", fourHundredKb);
        lawContent.cleanUp();

        assertEquals(2, lawContent.estimatedSize(), "1 MB holds two of the three ~0.4 MB entries");
    }
}
