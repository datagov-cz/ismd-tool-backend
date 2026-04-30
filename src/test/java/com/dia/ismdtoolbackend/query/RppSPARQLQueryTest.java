package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RppSPARQLQueryTest {

    @Test
    void agendaQueryParsesAndContainsTypeIri() {
        String q = RppSPARQLQuery.buildAgendaListQuery();
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(q.contains("https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/agenda"));
    }

    @Test
    void isvsQueryParsesContainsTypeIriAndOptional() {
        String q = RppSPARQLQuery.buildIsvsListQuery();
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(q.contains("https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/informační-systém-veřejné-správy"));
        assertTrue(q.contains("OPTIONAL"));
    }
}
