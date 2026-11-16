package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.OntologyUploadInProgressException;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets one upload per vocabulary run at a time within this instance. Keyed by slug, the value the
 * database keeps unique, so the existence check of a later upload sees the earlier one's commit.
 */
@Component
class UploadVocabularyLock {

    private final Set<String> uploading = ConcurrentHashMap.newKeySet();

    /** Claims the vocabulary until the returned claim is closed; rejects a second concurrent claim. */
    Claim claim(String graphName) {
        String slug = UtilityMethods.extractNameFromIRI(graphName);
        if (!uploading.add(slug)) {
            throw new OntologyUploadInProgressException("Slovník se právě nahrává: " + graphName);
        }
        return () -> uploading.remove(slug);
    }

    interface Claim extends AutoCloseable {
        @Override
        void close();
    }
}
