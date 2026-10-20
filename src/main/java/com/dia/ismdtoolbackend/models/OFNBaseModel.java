package com.dia.ismdtoolbackend.models;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.Set;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

@Getter
@Slf4j
public class OFNBaseModel {

    private final OntModel ontModel;

    public OFNBaseModel() {
        this(Set.of(POJEM), Set.of());
    }

    public OFNBaseModel(Set<String> requiredBaseClasses, Set<String> requiredProperties) {
        ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.setNsPrefix("cz", DEFAULT_NS);
        ontModel.setNsPrefix("rdf", RDF.getURI());
        ontModel.setNsPrefix("rdfs", RDFS.getURI());
        ontModel.setNsPrefix("owl", OWL2.getURI());
        ontModel.setNsPrefix("xsd", XSD);
        ontModel.setNsPrefix("čas", CAS_NS);
        ontModel.setNsPrefix("slovníky", SLOVNIKY_NS);

        createDynamicBaseModel(requiredBaseClasses, requiredProperties);
    }

    private void createDynamicBaseModel(Set<String> requiredBaseClasses, Set<String> requiredProperties) {
        OntClass pojemClass = null;
        if (requiredBaseClasses.contains(POJEM)) {
            pojemClass = ontModel.createClass(DEFAULT_NS + POJEM);
            pojemClass.addLabel(POJEM, "cs");
        }

        OntClass tridaClass = null;
        if (requiredBaseClasses.contains(TRIDA) && pojemClass != null) {
            tridaClass = ontModel.createClass(DEFAULT_NS + TRIDA);
            tridaClass.addLabel(TRIDA, "cs");
            tridaClass.addSuperClass(pojemClass);
        }

        OntClass casovyOkamzikClass = null;
        if (requiresTemporalSupport(requiredProperties)) {
            casovyOkamzikClass = ontModel.createClass(CAS_NS + CASOVY_OKAMZIK);
            casovyOkamzikClass.addLabel(CASOVY_OKAMZIK, "cs");
        }

        OntClass slovnikClass = null;
        if (requiresVocabularySupport(requiredProperties)) {
            slovnikClass = ontModel.createClass(SLOVNIKY_NS + SLOVNIK);
            slovnikClass.addLabel(SLOVNIK, "cs");
        }

        OntClass digitalDocumentClass = null;
        if (requiresDigitalDocumentSupport(requiredProperties)) {
            digitalDocumentClass = ontModel.createClass("http://schema.org/DigitalDocument");
            digitalDocumentClass.addProperty(RDFS.seeAlso, ontModel.createResource("http://schema.org/DigitalDocument"));
            digitalDocumentClass.addLabel("digitální-dokument", "cs");
        }

        if (requiredBaseClasses.contains(TSP) && tridaClass != null) {
            OntClass typSubjektuClass = ontModel.createClass(DEFAULT_NS + TSP);
            typSubjektuClass.addLabel(TSP, "cs");
            typSubjektuClass.addSuperClass(tridaClass);
        }

        if (requiredBaseClasses.contains(TOP) && tridaClass != null) {
            OntClass typObjektuClass = ontModel.createClass(DEFAULT_NS + TOP);
            typObjektuClass.addLabel(TOP, "cs");
            typObjektuClass.addSuperClass(tridaClass);
        }

        if (requiredBaseClasses.contains(DATOVY_TYP)) {
            OntClass datovyTyp = ontModel.createClass(DEFAULT_NS + DATOVY_TYP);
            datovyTyp.addLabel(DATOVY_TYP, "cs");
        }

        OntClass udajClass = null;
        if (requiredBaseClasses.contains(UDAJ) && pojemClass != null) {
            udajClass = ontModel.createClass(DEFAULT_NS + UDAJ);
            udajClass.addLabel(UDAJ, "cs");
            udajClass.addSuperClass(pojemClass);
        }

        OntClass polozkaClass = null;
        if (requiredBaseClasses.contains(POLOZKA_CISELNIKU)) {
            polozkaClass = ontModel.createClass(DEFAULT_NS + POLOZKA_CISELNIKU);
            polozkaClass.addLabel(POLOZKA_CISELNIKU, "cs");
        }

        if (requiredBaseClasses.contains(VEREJNY_UDAJ) && udajClass != null) {
            OntClass verejnyUdajClass = ontModel.createClass(DEFAULT_NS + VEREJNY_UDAJ);
            verejnyUdajClass.addLabel(VEREJNY_UDAJ, "cs");
            verejnyUdajClass.addSuperClass(udajClass);
        }

        OntClass neverejnyUdajClass = null;
        if (requiredBaseClasses.contains(NEVEREJNY_UDAJ) && udajClass != null) {
            neverejnyUdajClass = ontModel.createClass(DEFAULT_NS + NEVEREJNY_UDAJ);
            neverejnyUdajClass.addLabel(NEVEREJNY_UDAJ, "cs");
            neverejnyUdajClass.addSuperClass(udajClass);
        }

        if (requiredBaseClasses.contains(ZPUSOBY_SDILENI_UDAJE) && polozkaClass != null) {
            OntClass zpusobSdileniClass = ontModel.createClass(DEFAULT_NS + ZPUSOBY_SDILENI_UDAJE);
            zpusobSdileniClass.addLabel(ZPUSOBY_SDILENI_UDAJE, "cs");
            zpusobSdileniClass.addSuperClass(polozkaClass);
        }

        if (requiredBaseClasses.contains(ZPUSOB_ZISKANI_UDAJE) && polozkaClass != null) {
            OntClass zpusobZiskaniClass = ontModel.createClass(DEFAULT_NS + ZPUSOB_ZISKANI_UDAJE);
            zpusobZiskaniClass.addLabel(ZPUSOB_ZISKANI_UDAJE, "cs");
            zpusobZiskaniClass.addSuperClass(polozkaClass);
        }

        if (requiredBaseClasses.contains("číselník")) {
            OntClass ciselnikClass = ontModel.createClass(DEFAULT_NS + "číselník");
            ciselnikClass.addLabel("číselník", "cs");
        }

        if (requiredBaseClasses.contains("typ-vlastnosti")) {
            OntClass typVlastnostiClass = ontModel.createClass(DEFAULT_NS + "typ-vlastnosti");
            typVlastnostiClass.addLabel("typ-vlastnosti", "cs");
            if (pojemClass != null) {
                typVlastnostiClass.addSuperClass(pojemClass);
            }
        }

        createRequiredProperties(requiredProperties, pojemClass, neverejnyUdajClass,
                casovyOkamzikClass, slovnikClass, digitalDocumentClass);
    }

    private void createRequiredProperties(Set<String> requiredProperties, OntClass pojemClass,
                                          OntClass neverejnyUdajClass, OntClass casovyOkamzikClass,
                                          OntClass slovnikClass, OntClass digitalDocumentClass) {
        for (String propertyName : requiredProperties) {
            switch (propertyName) {
                case NAZEV:
                    if (pojemClass != null) {
                        OntProperty nazevProp = ontModel.createOntProperty(DEFAULT_NS + NAZEV);
                        nazevProp.addLabel(NAZEV, "cs");
                        nazevProp.addDomain(pojemClass);
                        nazevProp.addRange(org.apache.jena.vocabulary.XSD.xstring);
                        nazevProp.addProperty(RDF.type, OWL2.FunctionalProperty);
                    }
                    break;

                case ALTERNATIVNI_NAZEV:
                    if (pojemClass != null) {
                        OntProperty alternativniNazevProp = ontModel.createOntProperty(DEFAULT_NS + ALTERNATIVNI_NAZEV);
                        alternativniNazevProp.addLabel(ALTERNATIVNI_NAZEV, "cs");
                        alternativniNazevProp.addDomain(pojemClass);
                        alternativniNazevProp.addRange(RDF.langString);
                    }
                    break;

                case POPIS:
                    if (pojemClass != null) {
                        OntProperty popisProp = ontModel.createOntProperty(DEFAULT_NS + POPIS);
                        popisProp.addLabel(POPIS, "cs");
                        popisProp.addDomain(pojemClass);
                        popisProp.addRange(org.apache.jena.vocabulary.XSD.xstring);
                    }
                    break;

                case DEFINICE:
                    if (pojemClass != null) {
                        OntProperty definiceProp = ontModel.createOntProperty(DEFAULT_NS + DEFINICE);
                        definiceProp.addLabel(DEFINICE, "cs");
                        definiceProp.addDomain(pojemClass);
                        definiceProp.addRange(org.apache.jena.vocabulary.XSD.xstring);
                    }
                    break;

                case DEFINUJICI_USTANOVENI:
                    if (pojemClass != null) {
                        OntProperty definujiciUstanoveniProp = ontModel.createOntProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
                        definujiciUstanoveniProp.addLabel(DEFINUJICI_USTANOVENI, "cs");
                        definujiciUstanoveniProp.addDomain(pojemClass);
                        definujiciUstanoveniProp.addRange(RDFS.Resource);
                    }
                    break;

                case SOUVISEJICI_USTANOVENI:
                    if (pojemClass != null) {
                        OntProperty souvisejiciUstanoveniProp = ontModel.createOntProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);
                        souvisejiciUstanoveniProp.addLabel(SOUVISEJICI_USTANOVENI, "cs");
                        souvisejiciUstanoveniProp.addDomain(pojemClass);
                        souvisejiciUstanoveniProp.addRange(RDFS.Resource);
                    }
                    break;

                case DEFINUJICI_NELEGISLATIVNI_ZDROJ:
                    if (pojemClass != null) {
                        OntProperty definujiciNelegislativniZdrojProp = ontModel.createOntProperty(DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
                        definujiciNelegislativniZdrojProp.addLabel(DEFINUJICI_NELEGISLATIVNI_ZDROJ, "cs");
                        definujiciNelegislativniZdrojProp.addDomain(pojemClass);
                        definujiciNelegislativniZdrojProp.addRange(RDFS.Resource);
                    }
                    break;

                case SOUVISEJICI_NELEGISLATIVNI_ZDROJ:
                    if (pojemClass != null) {
                        OntProperty souvisejiciNelegislativniZdrojProp = ontModel.createOntProperty(DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
                        souvisejiciNelegislativniZdrojProp.addLabel(SOUVISEJICI_NELEGISLATIVNI_ZDROJ, "cs");
                        souvisejiciNelegislativniZdrojProp.addDomain(pojemClass);
                        souvisejiciNelegislativniZdrojProp.addRange(RDFS.Resource);
                    }
                    break;

                case "schema:url":
                    if (digitalDocumentClass != null) {
                        OntProperty schemaUrlProp = ontModel.createOntProperty("http://schema.org/url");
                        schemaUrlProp.addLabel("url", "en");
                        schemaUrlProp.addDomain(digitalDocumentClass);
                        schemaUrlProp.addRange(org.apache.jena.vocabulary.XSD.anyURI);
                    }
                    break;

                case JE_PPDF:
                    if (pojemClass != null) {
                        OntProperty jeSdilenVPpdfProp = ontModel.createOntProperty(DEFAULT_NS + JE_PPDF);
                        jeSdilenVPpdfProp.addLabel(JE_PPDF, "cs");
                        jeSdilenVPpdfProp.addDomain(pojemClass);
                        jeSdilenVPpdfProp.addRange(org.apache.jena.vocabulary.XSD.xboolean);
                    }
                    break;

                case AGENDA:
                    if (pojemClass != null) {
                        OntProperty agendaProp = ontModel.createOntProperty(DEFAULT_NS + AGENDA);
                        agendaProp.addLabel(AGENDA, "cs");
                        agendaProp.addDomain(pojemClass);
                        agendaProp.addRange(RDFS.Resource);
                    }
                    break;

                case AIS:
                    if (pojemClass != null) {
                        OntProperty aisProp = ontModel.createOntProperty(DEFAULT_NS + AIS);
                        aisProp.addLabel(AIS, "cs");
                        aisProp.addDomain(pojemClass);
                        aisProp.addRange(RDFS.Resource);
                    }
                    break;

                case USTANOVENI_NEVEREJNOST:
                    if (neverejnyUdajClass != null) {
                        OntProperty ustanoveniProp = ontModel.createOntProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
                        ustanoveniProp.addLabel(USTANOVENI_NEVEREJNOST, "cs");
                        ustanoveniProp.addDomain(neverejnyUdajClass);
                        ustanoveniProp.addRange(RDFS.Resource);
                    }
                    break;

                case DEFINICNI_OBOR:
                    OntProperty definicniOborProp = ontModel.createOntProperty(DEFAULT_NS + DEFINICNI_OBOR);
                    definicniOborProp.addLabel(DEFINICNI_OBOR, "cs");
                    definicniOborProp.addDomain(ontModel.createResource(RDF.Property.getURI()));
                    definicniOborProp.addRange(ontModel.createResource(RDFS.Class.getURI()));
                    break;

                case OBOR_HODNOT:
                    OntProperty oborHodnotProp = ontModel.createOntProperty(DEFAULT_NS + OBOR_HODNOT);
                    oborHodnotProp.addLabel(OBOR_HODNOT, "cs");
                    break;

                case NADRAZENA_TRIDA:
                    OntProperty nadrazenaTrida = ontModel.createOntProperty(DEFAULT_NS + NADRAZENA_TRIDA);
                    nadrazenaTrida.addLabel(NADRAZENA_TRIDA, "cs");
                    break;

                case ZPUSOB_SDILENI:
                    if (pojemClass != null) {
                        OntProperty zpusobSdileniProp = ontModel.createOntProperty(DEFAULT_NS + ZPUSOB_SDILENI);
                        zpusobSdileniProp.addLabel(ZPUSOB_SDILENI, "cs");
                        zpusobSdileniProp.addDomain(pojemClass);
                        zpusobSdileniProp.addRange(RDFS.Resource);
                    }
                    break;

                case ZPUSOB_ZISKANI:
                    if (pojemClass != null) {
                        OntProperty zpusobZiskaniProp = ontModel.createOntProperty(DEFAULT_NS + ZPUSOB_ZISKANI);
                        zpusobZiskaniProp.addLabel(ZPUSOB_ZISKANI, "cs");
                        zpusobZiskaniProp.addDomain(pojemClass);
                        zpusobZiskaniProp.addRange(RDFS.Resource);
                    }
                    break;

                case TYP_OBSAHU:
                    if (pojemClass != null) {
                        OntProperty typObsahuProp = ontModel.createOntProperty(DEFAULT_NS + TYP_OBSAHU);
                        typObsahuProp.addLabel(TYP_OBSAHU, "cs");
                        typObsahuProp.addDomain(pojemClass);
                        typObsahuProp.addRange(RDFS.Resource);
                    }
                    break;

                case OKAMZIK_POSLEDNI_ZMENY:
                    if (slovnikClass != null && casovyOkamzikClass != null) {
                        OntProperty okamzikPosledniZmenyProp = ontModel.createOntProperty(SLOVNIKY_NS + OKAMZIK_POSLEDNI_ZMENY);
                        okamzikPosledniZmenyProp.addLabel(OKAMZIK_POSLEDNI_ZMENY, "cs");
                        okamzikPosledniZmenyProp.addDomain(slovnikClass);
                        okamzikPosledniZmenyProp.addRange(casovyOkamzikClass);
                    }
                    break;

                case OKAMZIK_VYTVORENI:
                    if (slovnikClass != null && casovyOkamzikClass != null) {
                        OntProperty okamzikVytvoreniProp = ontModel.createOntProperty(SLOVNIKY_NS + OKAMZIK_VYTVORENI);
                        okamzikVytvoreniProp.addLabel(OKAMZIK_VYTVORENI, "cs");
                        okamzikVytvoreniProp.addDomain(slovnikClass);
                        okamzikVytvoreniProp.addRange(casovyOkamzikClass);
                    }
                    break;

                case DATUM:
                    if (casovyOkamzikClass != null) {
                        OntProperty datumProp = ontModel.createOntProperty(CAS_NS + DATUM);
                        datumProp.addLabel(DATUM, "cs");
                        datumProp.addDomain(casovyOkamzikClass);
                        datumProp.addRange(org.apache.jena.vocabulary.XSD.date);
                    }
                    break;

                case DATUM_A_CAS:
                    if (casovyOkamzikClass != null) {
                        OntProperty datumACasProp = ontModel.createOntProperty(CAS_NS + DATUM_A_CAS);
                        datumACasProp.addLabel(DATUM_A_CAS, "cs");
                        datumACasProp.addDomain(casovyOkamzikClass);
                        datumACasProp.addRange(org.apache.jena.vocabulary.XSD.dateTimeStamp);
                    }
                    break;

                default:
                    log.debug("Unknown property requested: {}", propertyName);
                    break;
            }
        }
    }

    private boolean requiresTemporalSupport(Set<String> requiredProperties) {
        return requiredProperties.contains(OKAMZIK_POSLEDNI_ZMENY) ||
                requiredProperties.contains(OKAMZIK_VYTVORENI) ||
                requiredProperties.contains(DATUM) ||
                requiredProperties.contains(DATUM_A_CAS);
    }

    private boolean requiresVocabularySupport(Set<String> requiredProperties) {
        return requiredProperties.contains(OKAMZIK_POSLEDNI_ZMENY) ||
                requiredProperties.contains(OKAMZIK_VYTVORENI);
    }

    private boolean requiresDigitalDocumentSupport(Set<String> requiredProperties) {
        return requiredProperties.contains(DEFINUJICI_NELEGISLATIVNI_ZDROJ) ||
                requiredProperties.contains(SOUVISEJICI_NELEGISLATIVNI_ZDROJ) ||
                requiredProperties.contains("schema:url");
    }
}