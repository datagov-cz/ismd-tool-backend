package com.dia.ismdtoolbackend.ofn;

import com.apicatalog.jsonld.JsonLdOptions;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFParserBuilder;
import org.apache.jena.riot.lang.LangJSONLD11;
import org.apache.jena.sparql.util.Context;

import java.io.InputStream;

/**
 * Parses JSON-LD offline: remote contexts are resolved by {@link OfnDocumentLoader}, so only the
 * bundled OFN context is ever used and nothing is fetched from the network.
 */
public final class OfnJsonLdParser {

    private OfnJsonLdParser() {
    }

    public static void parse(InputStream input, String baseIri, Model target) {
        builder(baseIri).source(input).parse(target);
    }

    public static void parse(String json, Model target) {
        builder(null).fromString(json).parse(target);
    }

    private static RDFParserBuilder builderBase() {
        return RDFParser.create().lang(Lang.JSONLD);
    }

    private static RDFParserBuilder builder(String baseIri) {
        JsonLdOptions options = new JsonLdOptions(
                new OfnDocumentLoader(OfnSpecification.bundled().contextUrl()));
        Context context = new Context();
        context.set(LangJSONLD11.JSONLD_OPTIONS, options);
        RDFParserBuilder builder = builderBase().context(context);
        if (baseIri != null) {
            builder.base(baseIri);
        }
        return builder;
    }
}
