package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.OntologyUploadInProgressException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadVocabularyLockTest {

    private static final String VOCABULARY = "https://slovník.gov.cz/agendový/100";
    private static final String OTHER_VOCABULARY = "https://slovník.gov.cz/agendový/200";

    private final UploadVocabularyLock lock = new UploadVocabularyLock();

    @Test
    void aClaimedVocabularyCannotBeClaimedAgain() {
        lock.claim(VOCABULARY);

        assertThatThrownBy(() -> lock.claim(VOCABULARY)).isInstanceOf(OntologyUploadInProgressException.class);
    }

    @Test
    void differentVocabulariesAreClaimedSideBySide() {
        lock.claim(VOCABULARY);

        assertThatCode(() -> lock.claim(OTHER_VOCABULARY)).doesNotThrowAnyException();
    }

    @Test
    void aClosedClaimFreesTheVocabulary() {
        lock.claim(VOCABULARY).close();

        assertThatCode(() -> lock.claim(VOCABULARY)).doesNotThrowAnyException();
    }

    @Test
    void vocabulariesSharingASlugExcludeEachOther() {
        lock.claim(VOCABULARY);

        assertThatThrownBy(() -> lock.claim("https://jiný.example/100"))
                .isInstanceOf(OntologyUploadInProgressException.class);
    }
}
