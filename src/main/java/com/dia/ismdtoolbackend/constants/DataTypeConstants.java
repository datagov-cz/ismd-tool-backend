package com.dia.ismdtoolbackend.constants;

import static com.dia.ismdtoolbackend.constants.ExportConstants.Json.RDFS_NS;
import static com.dia.ismdtoolbackend.constants.ExportConstants.Json.XSD_NS;

public class DataTypeConstants {

    public static final String XSD_BOOLEAN = XSD_NS + "boolean";
    public static final String XSD_DATE = XSD_NS + "date";
    public static final String XSD_TIME = XSD_NS + "time";
    public static final String XSD_DATETIME_STAMP = XSD_NS + "dateTimeStamp";
    public static final String XSD_INTEGER = XSD_NS + "integer";
    public static final String XSD_DOUBLE = XSD_NS + "double";
    public static final String XSD_ANY_URI = XSD_NS + "anyURI";
    public static final String XSD_STRING = XSD_NS + "string";
    public static final String RDFS_LITERAL = RDFS_NS + "Literal";

    private DataTypeConstants() {}
}
