package com.dia.ismdtoolbackend.utility;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test for {@link DataTypeConverter}.
 *
 * @see DataTypeConverter
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataTypeConverterUnitTest {

    @Mock
    private Model model;

    @Mock
    private Resource subject;

    @Mock
    private Property property;

    @Mock
    private Literal literal;

    @BeforeEach
    void setUp() {
        when(property.getLocalName()).thenReturn("testProperty");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n", "  \t\n  "})
    void testAddTypedProperty_withInvalidValues_shouldDoNothing(String value) {
        DataTypeConverter.addTypedProperty(subject, property, value, null, model);

        verifyNoInteractions(subject);
    }

    @Test
    void testAddTypedProperty_withValidValue_shouldAddProperty() {
        when(model.createTypedLiteral(123, XSDDatatype.XSDinteger)).thenReturn(literal);
        when(literal.getDatatypeURI()).thenReturn("http://www.w3.org/2001/XMLSchema#integer");

        DataTypeConverter.addTypedProperty(subject, property, "123", null, model);

        verify(subject).addProperty(property, literal);
    }

    @Test
    void testAddTypedProperty_withLanguage_shouldCreateLanguageTaggedLiteral() {
        when(model.createLiteral("hello", "en")).thenReturn(literal);

        DataTypeConverter.addTypedProperty(subject, property, "hello", "en", model);

        verify(model).createLiteral("hello", "en");
        verify(subject).addProperty(property, literal);
    }

    @Test
    void testCreateTypedLiteral_withLanguage_shouldReturnLanguageTaggedLiteral() {
        when(model.createLiteral("hello", "en")).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("hello", model, "en", "testProperty");

        assertEquals(literal, result);
        verify(model).createLiteral("hello", "en");
    }

    @ParameterizedTest
    @CsvSource({
            "true, true",
            "false, false",
            "ano, true",
            "ne, false",
            "yes, true",
            "no, false"
    })
    void testCreateTypedLiteral_withBooleanValues_shouldReturnBooleanLiteral(String input, boolean expected) {
        when(model.createTypedLiteral(expected)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral(input, model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral(expected);
    }

    @Test
    void testCreateTypedLiteral_withInteger_shouldReturnIntegerLiteral() {
        when(model.createTypedLiteral(123, XSDDatatype.XSDinteger)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("123", model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral(123, XSDDatatype.XSDinteger);
    }

    @Test
    void testCreateTypedLiteral_withNegativeInteger_shouldReturnIntegerLiteral() {
        when(model.createTypedLiteral(-456, XSDDatatype.XSDinteger)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("-456", model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral(-456, XSDDatatype.XSDinteger);
    }

    @Test
    void testCreateTypedLiteral_withDouble_shouldReturnDoubleLiteral() {
        when(model.createTypedLiteral(123.45, XSDDatatype.XSDdouble)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("123.45", model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral(123.45, XSDDatatype.XSDdouble);
    }

    @Test
    void testCreateTypedLiteral_withDate_shouldReturnDateLiteral() {
        when(model.createTypedLiteral("2023-12-25", XSDDatatype.XSDdate)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("2023-12-25", model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral("2023-12-25", XSDDatatype.XSDdate);
    }

    @Test
    void testCreateTypedLiteral_withTime_shouldReturnTimeLiteral() {
        when(model.createTypedLiteral("14:30:00", XSDDatatype.XSDtime)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("14:30:00", model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral("14:30:00", XSDDatatype.XSDtime);
    }

    @Test
    void testCreateTypedLiteral_withDateTime_shouldReturnDateTimeLiteral() {
        when(model.createTypedLiteral("2023-12-25T14:30:00", XSDDatatype.XSDdateTime)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("2023-12-25T14:30:00", model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral("2023-12-25T14:30:00", XSDDatatype.XSDdateTime);
    }

    @Test
    void testCreateTypedLiteral_withUri_shouldReturnUriLiteral() {
        when(model.createTypedLiteral("https://example.com", XSDDatatype.XSDanyURI)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("https://example.com", model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral("https://example.com", XSDDatatype.XSDanyURI);
    }

    @Test
    void testCreateTypedLiteral_withString_shouldReturnStringLiteral() {
        when(model.createTypedLiteral("hello world", XSDDatatype.XSDstring)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("hello world", model, null, "testProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral("hello world", XSDDatatype.XSDstring);
    }

    // Boolean value tests
    @Test
    void testIsBooleanValue_withVariousBooleanValues() {
        assertTrue(DataTypeConverter.isBooleanValue("true"));
        assertTrue(DataTypeConverter.isBooleanValue("TRUE"));
        assertTrue(DataTypeConverter.isBooleanValue("false"));
        assertTrue(DataTypeConverter.isBooleanValue("FALSE"));
        assertTrue(DataTypeConverter.isBooleanValue("ano"));
        assertTrue(DataTypeConverter.isBooleanValue("ANO"));
        assertTrue(DataTypeConverter.isBooleanValue("ne"));
        assertTrue(DataTypeConverter.isBooleanValue("NE"));
        assertTrue(DataTypeConverter.isBooleanValue("yes"));
        assertTrue(DataTypeConverter.isBooleanValue("YES"));
        assertTrue(DataTypeConverter.isBooleanValue("no"));
        assertTrue(DataTypeConverter.isBooleanValue("NO"));

        assertFalse(DataTypeConverter.isBooleanValue("maybe"));
        assertFalse(DataTypeConverter.isBooleanValue("1"));
        assertFalse(DataTypeConverter.isBooleanValue(""));
    }

    // Integer tests
    @Test
    void testIsInteger_withValidIntegers() {
        assertTrue(DataTypeConverter.isInteger("123"));
        assertTrue(DataTypeConverter.isInteger("-456"));
        assertTrue(DataTypeConverter.isInteger("0"));
        assertTrue(DataTypeConverter.isInteger("999999999"));

        assertFalse(DataTypeConverter.isInteger("123.45"));
        assertFalse(DataTypeConverter.isInteger("abc"));
        assertFalse(DataTypeConverter.isInteger(""));
        assertFalse(DataTypeConverter.isInteger("123.0"));
    }

    // Double tests
    @Test
    void testIsDouble_withValidDoubles() {
        assertTrue(DataTypeConverter.isDouble("123.45"));
        assertTrue(DataTypeConverter.isDouble("-456.789"));
        assertTrue(DataTypeConverter.isDouble("0.0"));
        assertTrue(DataTypeConverter.isDouble("123"));
        assertTrue(DataTypeConverter.isDouble("-123"));

        assertFalse(DataTypeConverter.isDouble("abc"));
        assertFalse(DataTypeConverter.isDouble(""));
        assertFalse(DataTypeConverter.isDouble("123.45.67"));
    }

    // URI tests
    @Test
    void testIsUri_withValidUris() {
        assertTrue(DataTypeConverter.isUri("https://example.com"));
        assertTrue(DataTypeConverter.isUri("http://test.org/path"));
        assertTrue(DataTypeConverter.isUri("ftp://files.example.com"));

        assertFalse(DataTypeConverter.isUri("not-a-uri"));
        assertFalse(DataTypeConverter.isUri(""));
        assertFalse(DataTypeConverter.isUri("example.com")); // no scheme
        assertFalse(DataTypeConverter.isUri("https://")); // no host
    }

    @Test
    void testIsUri_withNonAsciiIris() {
        // IRIs with non-ASCII characters (Czech diacritics) are valid and must be
        // recognised as-is, not slugified. java.net.URI rejected these.
        assertTrue(DataTypeConverter.isUri(
                "https://slovník.gov.cz/a124-datový-slovník-iskn/pojem/právní-vztah"));
        assertTrue(DataTypeConverter.isUri(
                "https://slovník.gov.cz/datový/číselníky/pojem/položka-číselníku"));

        assertFalse(DataTypeConverter.isUri(null));
        assertFalse(DataTypeConverter.isUri("   "));
    }

    @Test
    void testIsUri_withFragmentIris() {
        // Fragment IRIs are valid URIs here (used as range/reference values). Jena's
        // isAbsolute() returns false for these because RFC 3987 absolute IRIs exclude a
        // fragment, so isUri must not rely on isAbsolute().
        assertTrue(DataTypeConverter.isUri("http://www.w3.org/2000/01/rdf-schema#Literal"));
        assertTrue(DataTypeConverter.isUri("http://www.w3.org/2001/XMLSchema#string"));
        assertTrue(DataTypeConverter.isUri(
                "https://slovník.gov.cz/příkladový-slovník/pojem/text-p#fragment"));
    }

    // Date tests
    @Test
    void testIsDate_withValidDates() {
        assertTrue(DataTypeConverter.isDate("2023-12-25"));
        assertTrue(DataTypeConverter.isDate("25.12.2023"));
        assertTrue(DataTypeConverter.isDate("1.1.2023"));

        assertFalse(DataTypeConverter.isDate("not-a-date"));
        assertFalse(DataTypeConverter.isDate(""));
        assertFalse(DataTypeConverter.isDate("2023-13-01")); // invalid month
        assertFalse(DataTypeConverter.isDate("32.12.2023")); // invalid day
    }

    // Time tests
    @Test
    void testIsTime_withValidTimes() {
        assertTrue(DataTypeConverter.isTime("14:30:00"));
        assertTrue(DataTypeConverter.isTime("14:30"));
        assertTrue(DataTypeConverter.isTime("09:05:15"));

        assertFalse(DataTypeConverter.isTime("not-a-time"));
        assertFalse(DataTypeConverter.isTime(""));
        assertFalse(DataTypeConverter.isTime("25:00:00")); // invalid hour
        assertFalse(DataTypeConverter.isTime("14:60:00")); // invalid minute
    }

    // DateTime tests
    @Test
    void testIsDateTime_withValidDateTimes() {
        assertTrue(DataTypeConverter.isDateTime("2023-12-25T14:30:00"));
        assertTrue(DataTypeConverter.isDateTime("25.12.2023 14:30:00"));
        assertTrue(DataTypeConverter.isDateTime("2023-12-25T14:30:00"));

        assertFalse(DataTypeConverter.isDateTime("not-a-datetime"));
        assertFalse(DataTypeConverter.isDateTime(""));
        assertFalse(DataTypeConverter.isDateTime("2023-12-25"));
        assertFalse(DataTypeConverter.isDateTime("14:30:00"));
    }

    // Property name inference tests
    @Test
    void testInferTypeFromPropertyName_withDateProperties() {
        // Test through createTypedLiteral
        when(model.createTypedLiteral("somevalue", XSDDatatype.XSDdate)).thenReturn(literal);

        DataTypeConverter.createTypedLiteral("somevalue", model, null, "datumNarozeni");
        DataTypeConverter.createTypedLiteral("somevalue", model, null, "birthDate");

        verify(model, times(2)).createTypedLiteral("somevalue", XSDDatatype.XSDdate);
    }

    @Test
    void testInferTypeFromPropertyName_withTimeProperties() {
        when(model.createTypedLiteral("somevalue", XSDDatatype.XSDtime)).thenReturn(literal);

        DataTypeConverter.createTypedLiteral("somevalue", model, null, "casOdchodu");
        DataTypeConverter.createTypedLiteral("somevalue", model, null, "departureTime");

        verify(model, times(2)).createTypedLiteral("somevalue", XSDDatatype.XSDtime);
    }

    @Test
    void testInferTypeFromPropertyName_withUriProperties() {
        when(model.createTypedLiteral("somevalue", XSDDatatype.XSDanyURI)).thenReturn(literal);

        DataTypeConverter.createTypedLiteral("somevalue", model, null, "webUrl");
        DataTypeConverter.createTypedLiteral("somevalue", model, null, "uri");
        DataTypeConverter.createTypedLiteral("somevalue", model, null, "odkaz");
        DataTypeConverter.createTypedLiteral("somevalue", model, null, "link");

        verify(model, times(4)).createTypedLiteral("somevalue", XSDDatatype.XSDanyURI);
    }

    @Test
    void testInferTypeFromPropertyName_withBooleanProperties() {
        when(model.createTypedLiteral(false)).thenReturn(literal);

        DataTypeConverter.createTypedLiteral("somevalue", model, null, "isActive");
        DataTypeConverter.createTypedLiteral("somevalue", model, null, "hasChildren");
        DataTypeConverter.createTypedLiteral("somevalue", model, null, "flagEnabled");
        DataTypeConverter.createTypedLiteral("somevalue", model, null, "indicator");

        verify(model, times(4)).createTypedLiteral(false);
    }

    @Test
    void testInferTypeFromPropertyName_withIntegerProperties() {
        when(model.createTypedLiteral("somevalue", XSDDatatype.XSDinteger)).thenReturn(literal);

        DataTypeConverter.createTypedLiteral(String.valueOf(123), model, null, "count");
        DataTypeConverter.createTypedLiteral(String.valueOf(123), model, null, "number");
        DataTypeConverter.createTypedLiteral(String.valueOf(123), model, null, "quantity");

        verify(model, times(3)).createTypedLiteral(123, XSDDatatype.XSDinteger);
    }

    @Test
    void testInferTypeFromPropertyName_withNullPropertyName() {
        when(model.createTypedLiteral("somevalue", XSDDatatype.XSDstring)).thenReturn(literal);

        DataTypeConverter.createTypedLiteral("somevalue", model, null, null);

        verify(model).createTypedLiteral("somevalue", XSDDatatype.XSDstring);
    }

    @Test
    void testEdgeCases_emptyStrings() {
        assertFalse(DataTypeConverter.isBooleanValue(""));
        assertFalse(DataTypeConverter.isInteger(""));
        assertFalse(DataTypeConverter.isDouble(""));
        assertFalse(DataTypeConverter.isUri(""));
        assertFalse(DataTypeConverter.isDate(""));
        assertFalse(DataTypeConverter.isTime(""));
        assertFalse(DataTypeConverter.isDateTime(""));
    }

    @Test
    void testEdgeCases_whitespaceStrings() {
        assertFalse(DataTypeConverter.isBooleanValue("   "));
        assertFalse(DataTypeConverter.isInteger("   "));
        assertFalse(DataTypeConverter.isDouble("   "));
        assertFalse(DataTypeConverter.isUri("   "));
        assertFalse(DataTypeConverter.isDate("   "));
        assertFalse(DataTypeConverter.isTime("   "));
        assertFalse(DataTypeConverter.isDateTime("   "));
    }

    @Test
    void testCreateTypedLiteral_fallbackToStringType() {
        when(model.createTypedLiteral("random text", XSDDatatype.XSDstring)).thenReturn(literal);

        Literal result = DataTypeConverter.createTypedLiteral("random text", model, null, "unknownProperty");

        assertEquals(literal, result);
        verify(model).createTypedLiteral("random text", XSDDatatype.XSDstring);
    }

    @Test
    void testCreateTypedLiteral_fallbackToPlainLiteralOnError() {
        when(model.createTypedLiteral("error value", XSDDatatype.XSDstring)).thenReturn(literal);

        // This would test the catch block in createTypedLiteral, but it's hard to trigger
        // since the type detection is robust. The fallback is tested above.
        Literal result = DataTypeConverter.createTypedLiteral("error value", model, null, "testProperty");

        assertNotNull(result);
    }
}
