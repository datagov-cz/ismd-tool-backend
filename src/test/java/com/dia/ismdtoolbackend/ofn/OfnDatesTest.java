package com.dia.ismdtoolbackend.ofn;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class OfnDatesTest {

    @Test
    void nowIsInPragueToWholeSeconds() {
        Clock winter = Clock.fixed(Instant.parse("2026-01-15T10:20:30.987Z"), ZoneOffset.UTC);
        Clock summer = Clock.fixed(Instant.parse("2026-07-15T10:20:30Z"), ZoneOffset.UTC);

        assertThat(OfnDates.now(winter)).isEqualTo(OffsetDateTime.parse("2026-01-15T11:20:30+01:00"));
        assertThat(OfnDates.now(summer)).isEqualTo(OffsetDateTime.parse("2026-07-15T12:20:30+02:00"));
    }

    @Test
    void instantIsATypedBlankNodeWithAZonedDateTimeStamp() {
        Model model = ModelFactory.createDefaultModel();

        Resource instant = OfnDates.instant(model, OffsetDateTime.parse("2026-10-28T09:05:00+01:00"));

        assertThat(instant.isAnon()).isTrue();
        assertThat(instant.hasProperty(RDF.type, model.createResource(OfnIris.CASOVY_OKAMZIK))).isTrue();
        Literal value = instant.getProperty(model.createProperty(OfnIris.DATUM_A_CAS)).getLiteral();
        assertThat(value.getDatatypeURI()).isEqualTo(XSDDatatype.XSDdateTimeStamp.getURI());
        assertThat(value.getLexicalForm()).isEqualTo("2026-10-28T09:05:00+01:00");
        assertThat(value.getDatatype().isValid(value.getLexicalForm())).isTrue();
        assertThat(model.size()).isEqualTo(2);
    }
}
