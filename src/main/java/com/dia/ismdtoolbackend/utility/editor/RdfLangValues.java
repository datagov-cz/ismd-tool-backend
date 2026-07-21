package com.dia.ismdtoolbackend.utility.editor;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

/** Small RDF read / staging helpers shared by the concept and ontology editors. */
final class RdfLangValues {

    private RdfLangValues() {
    }

    /**
     * Collects all language-tagged literal values of {@code property} on
     * {@code resource} as a {@code lang -> value} map. Literals with no language
     * tag are keyed under {@link com.dia.constants.ExportConstants.Common#DEFAULT_LANG}.
     */
    static Map<String, String> byLanguage(Resource resource, Property property) {
        Map<String, String> valuesWithLang = new HashMap<>();
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getObject().asLiteral();
                String lang = literal.getLanguage() != null && !literal.getLanguage().isEmpty()
                        ? literal.getLanguage()
                        : DEFAULT_LANG;
                valuesWithLang.put(lang, literal.getString());
            }
        }
        return valuesWithLang;
    }

    /**
     * Collects all language-tagged literal values of {@code property} on {@code resource} as a
     * {@code lang -> values} map, keeping every value for a language rather than the last one.
     * Use for properties that legitimately repeat per language (e.g. {@code skos:altLabel});
     * {@link #byLanguage} collapses those to one. Values within a language are sorted so the
     * result is order-stable for equality comparison.
     */
    static Map<String, List<String>> allByLanguage(Resource resource) {
        Map<String, List<String>> valuesWithLang = new HashMap<>();
        StmtIterator iter = resource.listProperties(org.apache.jena.vocabulary.SKOS.altLabel);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getObject().asLiteral();
                String lang = literal.getLanguage() != null && !literal.getLanguage().isEmpty()
                        ? literal.getLanguage()
                        : DEFAULT_LANG;
                valuesWithLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(literal.getString());
            }
        }
        valuesWithLang.values().forEach(Collections::sort);
        return valuesWithLang;
    }

    /**
     * Picks the name used for IRI generation from a {@code lang -> value} map,
     * preferring {@code "cs"}, then {@link com.dia.constants.ExportConstants.Common#DEFAULT_LANG},
     * then any value. Returns {@code ""} for a null/empty map.
     */
    static String preferredName(Map<String, String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        if (names.containsKey("cs")) {
            return names.get("cs");
        }
        if (names.containsKey(DEFAULT_LANG)) {
            return names.get(DEFAULT_LANG);
        }
        return names.values().iterator().next();
    }

    /**
     * Stages removal of every {@code property} statement on {@code resource} and
     * cancels any matching additions already staged in {@code toAdd}, so a
     * remove-then-rewrite leaves only the intended new values.
     */
    static void removeAllByPredicate(Resource resource, Property property,
                                     Set<Statement> toRemove, Set<Statement> toAdd) {
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            toRemove.add(iter.next());
        }
        toAdd.removeIf(stmt ->
                stmt.getSubject().equals(resource) &&
                        stmt.getPredicate().equals(property));
    }
}
