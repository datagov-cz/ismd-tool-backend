package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConformanceIssueTest {

    @Test
    void shortConstructor_hasNoValue() {
        assertThat(new ConformanceIssue(Kind.VALUE_DROPPED, "a", "l", "m").value()).isNull();
    }

    @Test
    void onlyIncompleteKinds_areCompleteness() {
        assertThat(List.of(Kind.values())).filteredOn(Kind::isCompleteness)
                .containsExactlyInAnyOrder(Kind.CONCEPT_INCOMPLETE, Kind.VOCABULARY_INCOMPLETE);
    }

    @ParameterizedTest(name = "{0} issue(s) → {1}")
    @CsvSource({"1, 1 problém", "3, 3 problémy", "5, 5 problémů", "0, 0 problémů"})
    void exceptionMessage_usesTheCzechPlural(int count, String expected) {
        List<ConformanceIssue> issues = Collections.nCopies(count, new ConformanceIssue(Kind.VALUE_DROPPED, null, "", "x"));

        for (OfnConformanceException.Context context : OfnConformanceException.Context.values()) {
            assertThat(new OfnConformanceException(context, "v", issues).getMessage()).contains("(" + expected + ")");
        }
    }
}
