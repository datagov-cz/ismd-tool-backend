package com.dia.ismdtoolbackend.enums;

import com.dia.constants.DataTypeConstants;
import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyDataTypeTest {

    @Test
    void hasExactlyNineEntriesInDeclaredOrder() {
        assertThat(Arrays.stream(PropertyDataType.values())
                .map(PropertyDataType::code))
                .containsExactly(
                        "boolean", "date", "time", "dateTimeStamp",
                        "integer", "double", "anyURI", "string", "Literal");
    }

    @Test
    void iriSetMatchesDataTypeConstants() {
        Set<String> enumIris = Arrays.stream(PropertyDataType.values())
                .map(PropertyDataType::iri)
                .collect(Collectors.toSet());

        Set<String> expected = Set.of(
                DataTypeConstants.XSD_BOOLEAN,
                DataTypeConstants.XSD_DATE,
                DataTypeConstants.XSD_TIME,
                DataTypeConstants.XSD_DATETIME_STAMP,
                DataTypeConstants.XSD_INTEGER,
                DataTypeConstants.XSD_DOUBLE,
                DataTypeConstants.XSD_ANY_URI,
                DataTypeConstants.XSD_STRING,
                DataTypeConstants.RDFS_LITERAL
        );

        assertThat(enumIris).isEqualTo(expected);
    }

    @Test
    void toDtoCarriesCodeAndLabelOnly() {
        DataTypeDto dto = PropertyDataType.STRING.toDto();
        assertThat(dto.code()).isEqualTo("string");
        assertThat(dto.label()).isEqualTo("Řetězec");
    }

    @Test
    void fromValueAcceptsBareCode() {
        assertThat(PropertyDataType.fromValue("boolean"))
                .contains(PropertyDataType.BOOLEAN);
        assertThat(PropertyDataType.fromValue("Literal"))
                .contains(PropertyDataType.LITERAL);
    }

    @Test
    void fromValueAcceptsXsdPrefix() {
        assertThat(PropertyDataType.fromValue("xsd:string"))
                .contains(PropertyDataType.STRING);
        assertThat(PropertyDataType.fromValue("xsd:dateTimeStamp"))
                .contains(PropertyDataType.DATE_TIME_STAMP);
    }

    @Test
    void fromValueAcceptsRdfsPrefix() {
        assertThat(PropertyDataType.fromValue("rdfs:Literal"))
                .contains(PropertyDataType.LITERAL);
    }

    @Test
    void fromValueAcceptsFullXsdIri() {
        assertThat(PropertyDataType.fromValue("http://www.w3.org/2001/XMLSchema#integer"))
                .contains(PropertyDataType.INTEGER);
    }

    @Test
    void fromValueAcceptsFullRdfsIri() {
        assertThat(PropertyDataType.fromValue("http://www.w3.org/2000/01/rdf-schema#Literal"))
                .contains(PropertyDataType.LITERAL);
    }

    @Test
    void fromValueAcceptsCzechLabel() {
        assertThat(PropertyDataType.fromValue("Ano či ne"))
                .contains(PropertyDataType.BOOLEAN);
        assertThat(PropertyDataType.fromValue("Řetězec"))
                .contains(PropertyDataType.STRING);
        assertThat(PropertyDataType.fromValue("Text"))
                .contains(PropertyDataType.LITERAL);
    }

    @Test
    void fromValueReturnsEmptyForNullBlankOrUnknown() {
        assertThat(PropertyDataType.fromValue(null)).isEmpty();
        assertThat(PropertyDataType.fromValue("")).isEmpty();
        assertThat(PropertyDataType.fromValue("   ")).isEmpty();
        assertThat(PropertyDataType.fromValue("xsd:bogus")).isEmpty();
        assertThat(PropertyDataType.fromValue("not-a-type")).isEmpty();
    }

    @Test
    void codeToIriRoundTrip() {
        for (PropertyDataType type : PropertyDataType.values()) {
            Optional<PropertyDataType> roundTripped = PropertyDataType.fromValue(type.iri());
            assertThat(roundTripped)
                    .as("round-trip for %s", type)
                    .contains(type);

            assertThat(PropertyDataType.fromValue(type.code()))
                    .as("code lookup for %s", type)
                    .contains(type);
        }
    }
}
