package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.RelationType;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.expr.E_Exists;
import org.apache.jena.sparql.expr.E_LogicalOr;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementData;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The NKD search queries parsed with Jena: what they actually filter on, not how the text looks. */
class NKDSPARQLSearchQueryStructureTest {

    private static final String ONTOLOGY = "https://data.gov.cz/zdroj/slovník/osoby";

    static Stream<Arguments> roles() {
        return Stream.of(
                Arguments.of(ConceptType.TRIDA, OFN_NAMESPACE + TRIDA, OWL2.Class.getURI()),
                Arguments.of(ConceptType.VLASTNOST, OFN_NAMESPACE + VLASTNOST, OWL2.DatatypeProperty.getURI()),
                Arguments.of(ConceptType.VZTAH, OFN_NAMESPACE + VZTAH, OWL2.ObjectProperty.getURI()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roles")
    void pageQueryNarrowsToTheOfnRoleOrItsOwlType(ConceptType role, String ofnType, String owlType) {
        Query page = QueryFactory.create(NKDSPARQLSearchQuery.buildConceptSearchQuery(
                "osoba", "cs", 20, 0, null, null, role));

        assertThat(roleFilters(page)).contains(Set.of(ofnType, owlType));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roles")
    void countQueryNarrowsExactlyLikeThePageQuery(ConceptType role, String ofnType, String owlType) {
        Query count = QueryFactory.create(NKDSPARQLSearchQuery.buildConceptSearchCountQuery(
                "osoba", null, null, role));

        assertThat(roleFilters(count)).containsExactly(Set.of(ofnType, owlType));
    }

    @Test
    void unnarrowedQueriesHaveNoRoleFilter() {
        assertThat(roleFilters(QueryFactory.create(
                NKDSPARQLSearchQuery.buildConceptSearchCountQuery("osoba", null, null, null)))).isEmpty();
    }

    @Test
    void genericConceptIsNotARoleNarrowing() {
        assertThatThrownBy(() -> NKDSPARQLSearchQuery.buildConceptSearchCountQuery("osoba", null, null, ConceptType.KONCEPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KONCEPT is not a role-narrowing type");
        assertThatThrownBy(() -> NKDSPARQLSearchQuery.buildConceptSearchQuery("osoba", "cs", 20, 0, null, null, ConceptType.KONCEPT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    class CountQueryOntologyFilter {

        @Test
        void restrictsToTheSafeOntologyIrisOnly() {
            Query count = QueryFactory.create(NKDSPARQLSearchQuery.buildConceptSearchCountQuery(
                    "osoba", List.of(ONTOLOGY, "javascript:alert(1)", "not an iri"), null, null));

            assertThat(valuesIris(count)).containsExactly(ONTOLOGY);
        }

        @Test
        void onlyUnsafeIrisMeansNoRestriction() {
            Query count = QueryFactory.create(NKDSPARQLSearchQuery.buildConceptSearchCountQuery(
                    "osoba", List.of("javascript:alert(1)"), null, null));

            assertThat(valuesIris(count)).isEmpty();
        }
    }

    @Nested
    class CountQueryRelationFilter {

        @Test
        void singleRelationIsOneExistsFilter() {
            Query count = QueryFactory.create(NKDSPARQLSearchQuery.buildConceptSearchCountQuery(
                    "osoba", null, List.of(RelationType.EXACT_MATCH), null));

            assertThat(filters(count)).anySatisfy(f -> assertThat(f).isInstanceOf(E_Exists.class));
        }

        @Test
        void severalRelationsAreAlternatives() {
            Query count = QueryFactory.create(NKDSPARQLSearchQuery.buildConceptSearchCountQuery(
                    "osoba", null, List.of(RelationType.SUBCLASS, RelationType.PROPERTY_OF, RelationType.RELATIONSHIP_OF), null));

            assertThat(filters(count)).anySatisfy(f -> {
                assertThat(f).isInstanceOf(E_LogicalOr.class);
                assertThat(existsCount(f)).isEqualTo(3);
            });
        }
    }

    /** For each filter of the form EXISTS { ?resource a <A> } || EXISTS { ?resource a <B> }, the set {A, B}. */
    private static List<Set<String>> roleFilters(Query query) {
        List<Set<String>> result = new ArrayList<>();
        for (Expr filter : filters(query)) {
            if (filter instanceof E_LogicalOr or
                    && or.getArg1() instanceof E_Exists left && or.getArg2() instanceof E_Exists right) {
                Set<String> types = new HashSet<>();
                types.addAll(rdfTypesOf(left.getElement()));
                types.addAll(rdfTypesOf(right.getElement()));
                if (types.size() == 2) {
                    result.add(types);
                }
            }
        }
        return result;
    }

    /** Every FILTER expression, including those inside subqueries (the walker alone stops at them). */
    private static List<Expr> filters(Query query) {
        List<Expr> filters = new ArrayList<>();
        collectFilters(query.getQueryPattern(), filters);
        return filters;
    }

    private static void collectFilters(Element pattern, List<Expr> filters) {
        ElementWalker.walk(pattern, new ElementVisitorBase() {
            @Override
            public void visit(ElementFilter el) {
                filters.add(el.getExpr());
            }

            @Override
            public void visit(ElementSubQuery el) {
                collectFilters(el.getQuery().getQueryPattern(), filters);
            }
        });
    }

    private static Set<String> rdfTypesOf(Element element) {
        Set<String> types = new HashSet<>();
        ElementWalker.walk(element, new ElementVisitorBase() {
            @Override
            public void visit(ElementPathBlock el) {
                for (TriplePath path : el.getPattern().getList()) {
                    Triple t = path.asTriple();
                    if (t != null && t.getPredicate().equals(RDF.type.asNode()) && t.getObject().isURI()
                            && t.getSubject().isVariable() && t.getSubject().getName().equals("resource")) {
                        types.add(t.getObject().getURI());
                    }
                }
            }
        });
        return types;
    }

    private static int existsCount(Expr expr) {
        if (expr instanceof E_Exists) {
            return 1;
        }
        if (expr instanceof E_LogicalOr or) {
            return existsCount(or.getArg1()) + existsCount(or.getArg2());
        }
        return 0;
    }

    private static List<String> valuesIris(Query query) {
        List<String> iris = new ArrayList<>();
        ElementWalker.walk(query.getQueryPattern(), new ElementVisitorBase() {
            @Override
            public void visit(ElementData el) {
                el.getRows().forEach(binding -> binding.vars().forEachRemaining(v -> {
                    Node n = binding.get(v);
                    if (n != null && n.isURI()) {
                        iris.add(n.getURI());
                    }
                }));
            }
        });
        return iris;
    }
}
