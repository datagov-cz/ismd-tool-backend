package com.dia.ismdtoolbackend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

/** Enforces the class-structure rules described in {@code docs/CLASS_STRUCTURE.md}. */
class ClassStructureRulesTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final int MAX_LINES = 500;
    private static final int MAX_INJECTED_DEPENDENCIES = 13;

    /** Classes over {@link #MAX_LINES} today, capped at their current size. Shrink entries; never add one. */
    private static final Map<String, Integer> OVERSIZED_CLASSES = Map.of(
            "service/impl/EsbirkaServiceImpl.java", 845,
            "service/impl/DiagramServiceImpl.java", 824,
            "utility/editor/ConceptFieldUpdaters.java", 810,
            "utility/detail/OntologyDetailExtractor.java", 752,
            "service/impl/OntologyUploadServiceImpl.java", 729,
            "service/impl/ConceptDeviationComparator.java", 626,
            "service/impl/NkdDetailServiceImpl.java", 569);

    /** Singleton fields that are deliberately reassigned: volatile caches shared across requests. */
    private static final Set<String> MUTABLE_SINGLETON_FIELDS = Set.of(
            "ReconcilerRunService.lastReport",
            "NkdDetailServiceImpl.cachedTotalOntologies",
            "NkdDetailServiceImpl.cachedTotalConcepts");

    /** Beans that still inject a proxy of themselves. Remove entries as they are split; never add one. */
    private static final Set<String> SELF_INJECTING_BEANS = Set.of(
            "DiagramServiceImpl",
            "EsbirkaServiceImpl",
            "OntologyUploadServiceImpl");

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.dia.ismdtoolbackend");

    @Test
    void noClassExceedsTheLineLimit() throws IOException {
        Path root = MAIN_SOURCES.resolve("com/dia/ismdtoolbackend");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                long lines;
                try (Stream<String> content = Files.lines(file)) {
                    lines = content.count();
                }
                Integer cap = OVERSIZED_CLASSES.get(name);
                if (cap == null && lines > MAX_LINES) {
                    violations.add(name + " has " + lines + " lines (limit " + MAX_LINES + "): split it");
                } else if (cap != null && lines > cap) {
                    violations.add(name + " grew to " + lines + " lines (capped at " + cap + ")");
                } else if (cap != null && lines <= MAX_LINES) {
                    violations.add(name + " is now within the limit: remove it from OVERSIZED_CLASSES");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void springBeansStayWithinTheDependencyLimit() {
        classes().that().areMetaAnnotatedWith(Component.class)
                .should(haveAtMostConstructorParameters(MAX_INJECTED_DEPENDENCIES))
                .check(MAIN_CLASSES);
    }

    @Test
    void delegatesAreNotPublic() {
        classes().that(areDelegates()).should().notBePublic().check(MAIN_CLASSES);
    }

    @Test
    void delegatesDoNotOwnTransactionOrCacheBoundaries() {
        classes().that(areDelegates())
                .should().notBeAnnotatedWith(Transactional.class)
                .check(MAIN_CLASSES);
        methods().that().areDeclaredInClassesThat(areDelegates())
                .should().notBeAnnotatedWith(Transactional.class)
                .andShould().notBeAnnotatedWith(CacheEvict.class)
                .check(MAIN_CLASSES);
    }

    @Test
    void singletonsHoldNoReassignableState() {
        fields().that().areDeclaredInClassesThat().areMetaAnnotatedWith(Component.class)
                .and().areDeclaredInClassesThat().areNotAnnotatedWith(ConfigurationProperties.class)
                .and().areNotStatic()
                .and().areNotAnnotatedWith(Value.class)
                .and().areNotAnnotatedWith(Autowired.class)
                .and(areNotListedAsMutable())
                .should().beFinal()
                .check(MAIN_CLASSES);
    }

    @Test
    void beansDoNotInjectThemselves() {
        Set<String> selfInjecting = MAIN_CLASSES.stream()
                .filter(javaClass -> javaClass.isMetaAnnotatedWith(Component.class))
                .filter(javaClass -> javaClass.getFields().stream().anyMatch(field -> !field.getModifiers()
                        .contains(JavaModifier.STATIC) && isOwnBean(javaClass, field)))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(selfInjecting).isEqualTo(SELF_INJECTING_BEANS);
    }

    private static boolean isOwnBean(JavaClass owner, JavaField field) {
        JavaClass type = field.getRawType();
        return !type.isEquivalentTo(Object.class) && owner.isAssignableTo(type.getName());
    }

    /** The focused classes an orchestrator delegates to, identified by package and name. */
    private static DescribedPredicate<JavaClass> areDelegates() {
        return new DescribedPredicate<>("are delegates of an orchestrator") {
            @Override
            public boolean test(JavaClass javaClass) {
                String name = javaClass.getSimpleName();
                return switch (javaClass.getPackageName()) {
                    case "com.dia.ismdtoolbackend.service.impl" -> name.endsWith("Operation") || name.endsWith("Reader");
                    case "com.dia.ismdtoolbackend.service.search" -> name.startsWith("Ismd") && name.endsWith("Search");
                    case "com.dia.ismdtoolbackend.repository" -> name.startsWith("Fuseki");
                    case "com.dia.ismdtoolbackend.utility.creator" -> name.endsWith("Writer");
                    case "com.dia.ismdtoolbackend.utility.exporter.json" ->
                            name.endsWith("JsonMapper") && !name.equals("ConceptJsonMapper");
                    default -> false;
                };
            }
        };
    }

    private static DescribedPredicate<JavaField> areNotListedAsMutable() {
        return new DescribedPredicate<>("are not listed in MUTABLE_SINGLETON_FIELDS") {
            @Override
            public boolean test(JavaField field) {
                return !MUTABLE_SINGLETON_FIELDS.contains(
                        field.getOwner().getSimpleName() + "." + field.getName());
            }
        };
    }

    private static ArchCondition<JavaClass> haveAtMostConstructorParameters(int max) {
        return new ArchCondition<>("have at most " + max + " constructor parameters") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaConstructor constructor : javaClass.getConstructors()) {
                    int count = constructor.getRawParameterTypes().size();
                    if (count > max) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getName() + " takes " + count + " constructor parameters"));
                    }
                }
            }
        };
    }
}
