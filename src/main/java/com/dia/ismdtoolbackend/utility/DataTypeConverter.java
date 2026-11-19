package com.dia.ismdtoolbackend.utility;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.irix.IRIx;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

import java.util.Set;

/** Literal, IRI and XSD datatype helpers for the RDF writers. */
@Slf4j
public final class DataTypeConverter {

    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";

    private static final Set<String> XSD_TYPES = Set.of(
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

    private DataTypeConverter() {
    }

    /**
     * Adds {@code value} as a literal tagged with {@code lang}, or as a plain string when
     * {@code lang} is blank. Blank values are skipped.
     */
    public static void addLangLiteral(Resource subject, Property property, String value, String lang) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (lang == null || lang.isBlank()) {
            subject.addProperty(property, value);
        } else {
            subject.addProperty(property, value, lang);
        }
    }

    /**
     * True for an IRI with a scheme that is not a relative reference. Jena's parser accepts
     * non-ASCII IRIs (https://slovník.gov.cz/...), which {@code java.net.URI} rejects. Fragment
     * IRIs are accepted, so {@code isAbsolute()} (which excludes a fragment) is not used.
     */
    public static boolean isUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            IRIx iri = IRIx.create(value.trim());
            return iri.scheme() != null && !iri.isRelative();
        } catch (Exception e) {
            return false;
        }
    }

    /** True for a built-in XSD type local name, e.g. {@code "string"}. */
    public static boolean isValidXSDType(String xsdType) {
        return xsdType != null && XSD_TYPES.contains(xsdType);
    }

    /**
     * Full XSD IRI for {@code "string"}, {@code "xsd:string"} or an {@code http://} IRI (returned
     * as is); null for a blank or unknown type.
     */
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