package com.dia.ismdtoolbackend.ofn;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdErrorCode;
import com.apicatalog.jsonld.document.Document;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.http.media.MediaType;
import com.apicatalog.jsonld.loader.DocumentLoader;
import com.apicatalog.jsonld.loader.DocumentLoaderOptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * JSON-LD document loader that never goes to the network: the OFN context URL resolves to the
 * bundled copy, every other remote document is refused.
 */
public final class OfnDocumentLoader implements DocumentLoader {

    private final String contextUrl;
    private final byte[] contextBytes;

    public OfnDocumentLoader(String contextUrl) {
        this.contextUrl = contextUrl;
        this.contextBytes = readBundledContext();
    }

    @Override
    public Document loadDocument(URI url, DocumentLoaderOptions options) throws JsonLdError {
        if (url != null && contextUrl.equals(decode(url))) {
            return JsonDocument.of(MediaType.JSON_LD, new ByteArrayInputStream(contextBytes));
        }
        throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                "Vzdálený JSON-LD kontext není povolen: " + url
                        + " (podporován je pouze kontext OFN slovníků " + contextUrl + ")");
    }

    /** A URI given as raw UTF-8 or percent-encoded compares as the same string. */
    private static String decode(URI url) {
        return URLDecoder.decode(url.toString().replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static byte[] readBundledContext() {
        try (InputStream in = OfnDocumentLoader.class.getClassLoader()
                .getResourceAsStream(OfnSpecification.CONTEXT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled OFN resource: " + OfnSpecification.CONTEXT_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
