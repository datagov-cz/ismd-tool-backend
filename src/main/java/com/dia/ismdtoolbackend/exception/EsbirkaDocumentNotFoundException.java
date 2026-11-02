package com.dia.ismdtoolbackend.exception;

/**
 * The e-Sbírka REST API has no document, version or fragment under the requested staleUrl.
 * An {@link IllegalArgumentException}, so it maps to HTTP 400 like the SPARQL client's
 * "… nebyl nalezen" errors.
 */
public class EsbirkaDocumentNotFoundException extends IllegalArgumentException {

    public EsbirkaDocumentNotFoundException(String message) {
        super(message);
    }
}
