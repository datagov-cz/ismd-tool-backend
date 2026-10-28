# Privacy provisions under pre-OFN predicates (finding C4): l111:ustanovení-dokládající-neveřejnost-údaje,
# the vocabulary-namespace copy, or either local name in any other namespace. On a Neveřejný údaj they
# move to l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost. On any other concept they are stale
# (the concept was made public, or never classified) and are dropped (decided 2026-10-28).
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>
PREFIX l111:     <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/>

INSERT { GRAPH ?graph { ?concept l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost ?provision } }
WHERE {
  GRAPH ?graph {
    ?concept a l111:neveřejný-údaj ;
             ?legacy ?provision .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    FILTER(?legacy != l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost)
    FILTER(REPLACE(STR(?legacy), "^.*[/#]", "") IN
           ("ustanovení-dokládající-neveřejnost-údaje", "je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost"))
    FILTER(isIRI(?provision))
  }
} ;

DELETE { GRAPH ?graph { ?concept ?legacy ?provision } }
WHERE {
  GRAPH ?graph {
    ?concept ?legacy ?provision .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    FILTER(?legacy != l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost)
    FILTER(REPLACE(STR(?legacy), "^.*[/#]", "") IN
           ("ustanovení-dokládající-neveřejnost-údaje", "je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost"))
  }
}
