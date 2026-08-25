package com.dia.ismdtoolbackend.temporary;

import com.dia.ismdtoolbackend.utility.validation.ConceptInputValidator;
import com.dia.ismdtoolbackend.utility.validation.ConceptInputView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TEMPORARY — proves {@link LegalSourceIriValidationSpec} accepts and rejects exactly what
 * the production {@link ConceptInputValidator} does, so the FE can port the spec verbatim.
 * Delete alongside the spec class.
 */
class LegalSourceIriValidationSpecParityTest {

    private static ConceptInputView viewWithLegalSource(String value) {
        return new ConceptInputView(
                List.of(value), null, null, null, null, null,
                null, null, null, null, null, null, null, null, "Třída", "á");
    }

    @Test
    void specMatchesProductionValidatorOnEveryReferenceCase() {
        for (Object[] testCase : LegalSourceIriValidationSpec.referenceCases()) {
            String input = (String) testCase[0];
            boolean expected = (Boolean) testCase[1];

            assertEquals(expected, LegalSourceIriValidationSpec.isValidLegalSourceIri(input),
                    "spec disagrees with declared expectation for: " + input);

            List<ConceptInputValidator.InvalidInput> problems =
                    ConceptInputValidator.validate(viewWithLegalSource(input));
            assertEquals(expected, problems.isEmpty(),
                    "production validator disagrees for: " + input + " -> " + problems);
        }
    }

    @Test
    void specMatchesProductionValidatorOnBlankAndNullEntries() {
        for (String blank : new String[]{null, "", "   "}) {
            ConceptInputView view = new ConceptInputView(
                    java.util.Collections.singletonList(blank), null, null, null, null, null,
                    null, null, null, null, null, null, null, null, "Třída", "á");
            assertEquals(ConceptInputValidator.validate(view).isEmpty(),
                    LegalSourceIriValidationSpec.validateLegalSourceList(
                            "definingLegalSource", java.util.Collections.singletonList(blank)).isEmpty(),
                    "blank handling differs for: [" + blank + "]");
        }
    }
}