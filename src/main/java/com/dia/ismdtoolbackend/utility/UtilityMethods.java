package com.dia.ismdtoolbackend.utility;

import lombok.experimental.UtilityClass;

/** IRI and code-list value helpers (originally from the retired ismd-validator-common library). */
@UtilityClass
public class UtilityMethods {

    public boolean isValidFirstChar(char ch) {
        return isLetterOrUnderscore(ch) || Character.isDigit(ch) || ch == ':';
    }

    public boolean isValidSubsequentChar(char ch) {
        return isLetterOrUnderscore(ch) || Character.isDigit(ch) || ch == '-' ||
                ch == '.' || ch == ':' ||
                ch == '·' ||
                (ch >= '̀' && ch <= 'ͯ') ||
                (ch >= '‿' && ch <= '⁀');
    }

    public boolean isLetterOrUnderscore(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    public boolean isValidUrl(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public String ensureNamespaceEndsWithDelimiter(String namespace) {
        if (!namespace.endsWith("/") && !namespace.endsWith("#")) {
            return namespace + "/";
        }
        return namespace;
    }

    /**
     * Removes trailing slashes from an IRI, e.g. ".../par_2/odst_1/" becomes ".../par_2/odst_1".
     * The "//" following the scheme is never touched.
     */
    public String removeTrailingSlash(String iri) {
        if (iri == null) {
            return null;
        }

        String result = iri;
        while (result.endsWith("/") && !result.endsWith("://")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    public String sanitizeForIRI(String input) {
        if (input == null || input.isEmpty()) {
            return "unnamed";
        }

        String lowercased = input.toLowerCase();

        StringBuilder result = new StringBuilder();
        boolean isFirst = true;

        for (int i = 0; i < lowercased.length(); i++) {
            char ch = lowercased.charAt(i);

            if (isFirst) {
                if (UtilityMethods.isValidFirstChar(ch)) {
                    result.append(ch);
                    isFirst = false;
                } else {
                    result.append('-');
                }
            } else {
                if (UtilityMethods.isValidSubsequentChar(ch)) {
                    result.append(ch);
                } else {
                    result.append('-');
                }
            }
        }

        String sanitized = result.toString().replaceAll("-+", "-");
        sanitized = sanitized.replaceAll("-+$", "");
        sanitized = sanitized.replaceAll("^-+", "");

        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }

    public boolean isValidAgendaValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        value = removeTrailingSlash(value.trim());

        if (value.matches("^(\\d+)$")) {
            return true;
        }

        if (value.matches("^A(\\d+)$")) {
            return true;
        }

        return value.matches("^https://.*?/agenda/A(\\d+)$");
    }

    public String transformAgendaValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        value = removeTrailingSlash(value.trim());

        if (value.matches("^(\\d+)$")) {
            return "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A" + value;
        }

        if (value.matches("^A(\\d+)$")) {
            return "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/" + value;
        }

        if (value.matches("^https://.*?/agenda/A(\\d+)$")) {
            String agendaCode = value.replaceAll("^https://.*?/agenda/(A\\d+)$", "$1");
            return "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/" + agendaCode;
        }

        return value;
    }

    public boolean isValidAISValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        value = removeTrailingSlash(value.trim());

        if (value.matches("^(\\d+)$")) {
            return true;
        }

        return value.matches("^https://.*?/isvs/(\\d+)$");
    }

    public String transformAISValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        value = removeTrailingSlash(value.trim());

        if (value.matches("^(\\d+)$")) {
            return "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/" + value;
        }

        if (value.matches("^https://.*?/isvs/(\\d+)$")) {
            String isvsCode = value.replaceAll("^https://.*?/isvs/(\\d+)$", "$1");
            return "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/" + isvsCode;
        }

        return value;
    }

    public static boolean isValidIRI(String iri) {
        if (iri == null || iri.trim().isEmpty()) {
            return false;
        }

        String trimmed = iri.trim();

        if (containsInvalidPatterns(trimmed)) {
            return false;
        }

        if (!hasValidIRIStructure(trimmed)) {
            return false;
        }

        return isValidIRIPattern(trimmed);
    }

    private static boolean containsInvalidPatterns(String iri) {
        String lower = iri.toLowerCase();

        return lower.contains(" text") ||
                lower.startsWith("ne-") ||
                lower.equals("invalid") ||
                lower.equals("n/a") ||
                lower.equals("tbd") ||
                lower.equals("todo") ||
                iri.contains(" ") ||
                iri.contains("\t") ||
                iri.contains("\n") ||
                iri.contains("\r");
    }

    private static boolean hasValidIRIStructure(String iri) {
        if (!iri.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return false;
        }

        if (iri.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            String afterScheme = iri.substring(iri.indexOf("://") + 3);
            return !afterScheme.isEmpty();
        }

        return true;
    }

    private static boolean isValidIRIPattern(String iri) {
        if (!iri.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return false;
        }

        if (iri.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            String afterScheme = iri.substring(iri.indexOf("://") + 3);

            if (afterScheme.isEmpty()) {
                return false;
            }

            return !afterScheme.contains(" ") &&
                    !afterScheme.contains("\t") &&
                    !afterScheme.contains("\n") &&
                    !afterScheme.contains("\r") &&
                    !afterScheme.contains("<") &&
                    !afterScheme.contains(">") &&
                    !afterScheme.contains("\"") &&
                    !afterScheme.contains("{") &&
                    !afterScheme.contains("}") &&
                    !afterScheme.contains("|") &&
                    !afterScheme.contains("\\") &&
                    !afterScheme.contains("^") &&
                    !afterScheme.contains("`");
        }

        return true;
    }

    public boolean isBooleanValue(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase();
        return normalized.equals("true") || normalized.equals("false") ||
                normalized.equals("ano") || normalized.equals("ne") ||
                normalized.equals("yes") || normalized.equals("no");
    }

    public String extractNameFromIRI(String iri) {
        if (iri == null) return null;

        int lastSlash = iri.lastIndexOf('/');
        int lastHash = iri.lastIndexOf('#');
        int lastSeparator = Math.max(lastSlash, lastHash);

        if (lastSeparator >= 0 && lastSeparator < iri.length() - 1) {
            return iri.substring(lastSeparator + 1);
        }

        return iri;
    }

    public boolean isValidSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return false;
        }

        source = source.trim();

        if (source.length() < 3) {
            return false;
        }

        return !source.matches("^[^a-zA-Z0-9]*$");
    }
}
