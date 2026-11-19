package com.dia.ismdtoolbackend.ofn;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Every {@link OfnTerms} constant names a term of the bundled context, so a renamed term fails the build. */
class OfnTermsTest {

    @Test
    void everyConstantIsATermOfTheBundledContext() throws IllegalAccessException {
        OfnContextIndex context = OfnSpecification.bundled().contextIndex();
        Set<String> names = new HashSet<>();
        context.allTerms().values().forEach(term -> names.add(term.name()));
        names.add(context.idAlias());
        names.add(context.typeAlias());

        List<String> constants = constants(OfnTerms.class);

        assertThat(constants).isNotEmpty();
        assertThat(constants).as("OfnTerms constants missing from kontext.jsonld").allMatch(names::contains);
    }

    static List<String> constants(Class<?> type) throws IllegalAccessException {
        List<String> values = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                values.add((String) field.get(null));
            }
        }
        return values;
    }
}
