package com.dia.ismdtoolbackend.utility;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;
@Slf4j
public class DataTypeConverter {

    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+$");
    private static final Pattern DOUBLE_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";

    private static final DateTimeFormatter[] DATE_FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy")
    };

    private static final DateTimeFormatter[] TIME_FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_TIME,
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm")
    };

    private static final DateTimeFormatter[] DATETIME_FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    };

    private DataTypeConverter() {
    }

    public static void addTypedProperty(Resource subject, Property property, String value, String lang, Model model) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }

        try {
            Literal typedLiteral = createTypedLiteral(value, model, lang, property.getLocalName());
            subject.addProperty(property, typedLiteral);
            log.debug("Added property {} with value {} as {}",
                    property.getLocalName(), value, typedLiteral.getDatatypeURI());
        } catch (Exception e) {
            log.warn("Type detection failed for value '{}' on property '{}': {}. Using rdfs:Literal instead.",
                    value, property.getLocalName(), e.getMessage());
            if (lang != null && !lang.isEmpty()) {
                subject.addProperty(property, value, lang);
            } else {
                subject.addProperty(property, value);
            }
        }
    }

    public static Literal createTypedLiteral(String value, Model model, String lang, String propertyName) {
        if (lang != null && !lang.isEmpty()) {
            if (shouldUseRdfLangString(propertyName)) {
                return createRdfLangStringLiteral(value, lang, model);
            }
            return model.createLiteral(value, lang);
        }

        DetectionResult result = detectDataType(value, propertyName);

        if (result.getXsdType() != null) {
            return switch (result.getXsdType()) {
                case "xsd:boolean" -> {
                    boolean boolValue = "true".equalsIgnoreCase(value) ||
                            "ano".equalsIgnoreCase(value) ||
                            "yes".equalsIgnoreCase(value);
                    yield model.createTypedLiteral(boolValue);
                }
                case "xsd:integer" -> model.createTypedLiteral(Integer.parseInt(value), XSDDatatype.XSDinteger);
                case "xsd:double" -> model.createTypedLiteral(Double.parseDouble(value), XSDDatatype.XSDdouble);
                case "xsd:date" -> model.createTypedLiteral(value, XSDDatatype.XSDdate);
                case "xsd:time" -> model.createTypedLiteral(value, XSDDatatype.XSDtime);
                case "xsd:dateTime" -> model.createTypedLiteral(value, XSDDatatype.XSDdateTime);
                case "xsd:dateTimeStamp" -> model.createTypedLiteral(value, XSDDatatype.XSDdateTimeStamp);
                case "xsd:anyURI" -> model.createTypedLiteral(value, XSDDatatype.XSDanyURI);
                case "xsd:string" -> model.createTypedLiteral(value, XSDDatatype.XSDstring);
                case "rdf:langString" -> createRdfLangStringLiteral(value, "cs", model); // Default to Czech
                default -> {
                    log.warn("Unrecognized XSD type: {}. Defaulting to rdfs:Literal", result.getXsdType());
                    yield model.createLiteral(value);
                }
            };
        }

        log.warn("Could not detect datatype for value '{}' of property '{}'. Defaulting to rdfs:Literal",
                value, propertyName);
        return model.createLiteral(value);
    }

    public static Literal createRdfLangStringLiteral(String value, String lang, Model model) {
        try {
            return model.createLiteral(value, lang);
        } catch (Exception e) {
            log.warn("Failed to create rdf:langString literal for '{}': {}. Using regular language-tagged literal.",
                    value, e.getMessage());
            return model.createLiteral(value, lang);
        }
    }

    private static boolean shouldUseRdfLangString(String propertyName) {
        if (propertyName == null) {
            return false;
        }

        String lowerName = propertyName.toLowerCase();

        return lowerName.contains(ALTERNATIVNI_NAZEV.toLowerCase()) ||
                lowerName.equals("alternativní-název") ||
                lowerName.equals("alternative-name") ||
                lowerName.contains("název") && lowerName.contains("alternativní");
    }

    @Getter
    private static class DetectionResult {
        private final String xsdType;
        private final String message;

        public DetectionResult(String xsdType, String message) {
            this.xsdType = xsdType;
            this.message = message;
        }
    }

    private static DetectionResult detectDataType(String value, String propertyName) {
        if (isBooleanValue(value)) {
            return new DetectionResult("xsd:boolean", "Detected as boolean");
        }

        if (isUri(value)) {
            return new DetectionResult("xsd:anyURI", "Detected as URI");
        }

        if (isDateTimeStamp(value)) {
            return new DetectionResult("xsd:dateTimeStamp", "Detected as dateTimeStamp");
        }

        if (isDate(value)) {
            return new DetectionResult("xsd:date", "Detected as date");
        }

        if (isTime(value)) {
            return new DetectionResult("xsd:time", "Detected as time");
        }

        if (isDateTime(value)) {
            return new DetectionResult("xsd:dateTime", "Detected as dateTime");
        }

        if (isInteger(value)) {
            return new DetectionResult("xsd:integer", "Detected as integer");
        }

        if (isDouble(value)) {
            return new DetectionResult("xsd:double", "Detected as double");
        }

        String inferredType = inferTypeFromPropertyName(propertyName);
        if (inferredType != null) {
            return new DetectionResult(inferredType, "Inferred from property name");
        }

        if (shouldUseRdfLangString(propertyName)) {
            return new DetectionResult("rdf:langString", "Detected as rdf:langString");
        }

        return new DetectionResult("xsd:string", "Default to string");
    }

    private static String inferTypeFromPropertyName(String propertyName) {
        if (propertyName == null) {
            return null;
        }

        String lowerName = propertyName.toLowerCase();

        if (lowerName.contains("datum") && lowerName.contains("čas")) {
            return "xsd:dateTimeStamp";
        }

        if (lowerName.contains("datum") || lowerName.contains("date")) {
            return "xsd:date";
        }

        if (lowerName.contains("cas") || lowerName.contains("time")) {
            return "xsd:time";
        }

        if (lowerName.contains("okamžik") || lowerName.contains("timestamp")) {
            return "xsd:dateTimeStamp";
        }

        if (lowerName.contains("url") || lowerName.contains("uri") ||
                lowerName.contains("odkaz") || lowerName.contains("link")) {
            return "xsd:anyURI";
        }

        if (lowerName.contains("boolean") || lowerName.contains("flag") ||
                lowerName.contains("indicator") || lowerName.contains("is") ||
                lowerName.contains("has")) {
            return "xsd:boolean";
        }

        if (lowerName.contains("count") || lowerName.contains("number") ||
                lowerName.contains("integer") || lowerName.contains("quantity")) {
            return "xsd:integer";
        }

        return null;
    }

    public static boolean isBooleanValue(String value) {
        return "true".equalsIgnoreCase(value) ||
                "false".equalsIgnoreCase(value) ||
                "ano".equalsIgnoreCase(value) ||
                "ne".equalsIgnoreCase(value) ||
                "yes".equalsIgnoreCase(value) ||
                "no".equalsIgnoreCase(value);
    }

    public static boolean isInteger(String value) {
        return INTEGER_PATTERN.matcher(value).matches();
    }

    public static boolean isDouble(String value) {
        return DOUBLE_PATTERN.matcher(value).matches();
    }

    public static boolean isUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            // Use Jena's IRI parser rather than java.net.URI: IRIs in the vocabulary
            // routinely contain non-ASCII characters (e.g. https://slovník.gov.cz/...),
            // which java.net.URI rejects with a URISyntaxException.
            //
            // Accept any IRI that carries a scheme and is not a relative reference. We
            // deliberately do NOT use isAbsolute(): per RFC 3987 an "absolute" IRI excludes
            // a fragment, so isAbsolute() returns false for fragment IRIs such as
            // "http://www.w3.org/2000/01/rdf-schema#Literal" — which are legitimate range
            // and reference values here. Requiring scheme + !relative still rejects bare
            // names ("example.com", "not-a-uri") and scheme-only values ("https://", which
            // throws).
            org.apache.jena.irix.IRIx iri = org.apache.jena.irix.IRIx.create(value.trim());
            return iri.scheme() != null && !iri.isRelative();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isDate(String value) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate.parse(value, formatter);
                return true;
            } catch (DateTimeParseException e) {
                // continue to next check
            }
        }
        return false;
    }

    public static boolean isTime(String value) {
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                LocalTime.parse(value, formatter);
                return true;
            } catch (DateTimeParseException e) {
                // continue to next check
            }
        }
        return false;
    }

    public static boolean isDateTime(String value) {
        for (DateTimeFormatter formatter : DATETIME_FORMATTERS) {
            try {
                LocalDateTime.parse(value, formatter);
                return true;
            } catch (DateTimeParseException e) {
                // continue to next check
            }
        }
        return false;
    }

    public static boolean isDateTimeStamp(String value) {
        if (value == null) return false;

        return value.matches(".*T.*[+-]\\d{2}:\\d{2}$") ||
                value.matches(".*T.*Z$") ||
                value.endsWith("+02:00") ||
                value.endsWith("+01:00") ||
                value.endsWith("-00:00");
    }

    public static boolean isValidXSDType(String xsdType) {
        Set<String> validXSDTypes = Set.of(
                "string", "boolean", "decimal", "float", "double", "duration",
                "dateTime", "dateTimeStamp", "time", "date", "gYearMonth", "gYear", "gMonthDay",
                "gDay", "gMonth", "hexBinary", "base64Binary", "anyURI", "QName",
                "NOTATION", "normalizedString", "token", "language", "NMTOKEN",
                "NMTOKENS", "Name", "NCName", "ID", "IDREF", "IDREFS", "ENTITY",
                "ENTITIES", "integer", "nonPositiveInteger", "negativeInteger",
                "long", "int", "short", "byte", "nonNegativeInteger",
                "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte",
                "positiveInteger"
        );

        return validXSDTypes.contains(xsdType);
    }

    public static String getXSDTypeURI(String xsdType) {
        if (xsdType == null || xsdType.trim().isEmpty()) {
            return null;
        }

        String cleanType = xsdType.trim();

        if (cleanType.startsWith("http://")) {
            return cleanType;
        }

        if (cleanType.startsWith("xsd:")) {
            cleanType = cleanType.substring(4);
        }

        if (!isValidXSDType(cleanType)) {
            log.warn("Invalid XSD type: {}. Returning null.", xsdType);
            return null;
        }

        return XSD_NAMESPACE + cleanType;
    }
}
