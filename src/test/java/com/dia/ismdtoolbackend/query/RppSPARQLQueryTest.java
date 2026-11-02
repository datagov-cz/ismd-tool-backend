package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RppSPARQLQueryTest {

    @Test
    void agendaQueryParsesAndContainsTypeIri() {
        String q = RppSPARQLQuery.buildAgendaListQuery();
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).contains("https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/agenda");
    }

    @Test
    void isvsQueryParsesContainsTypeIriAndOptional() {
        String q = RppSPARQLQuery.buildIsvsListQuery();
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q)
                .contains("https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/informační-systém-veřejné-správy");
        assertThat(q)
                .contains("https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/určený-informační-systém-veřejné-správy");
        assertThat(q).contains("OPTIONAL");
    }
}
