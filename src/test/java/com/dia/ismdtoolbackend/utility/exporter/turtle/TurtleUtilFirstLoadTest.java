package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The Turtle utilities load cleanly as the first Jena user in a JVM. In this test JVM Jena is already
 * initialised, so each class is loaded through a fresh class loader that holds its own, uninitialised Jena.
 */
class TurtleUtilFirstLoadTest {

    @ParameterizedTest
    @ValueSource(classes = {TurtleFilterUtil.class, TurtleFormatterUtil.class})
    void initialisesAsTheFirstJenaClassLoaded(Class<?> utility) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader fresh = new URLClassLoader(classpath(), ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(fresh);
            Class<?> isolated = Class.forName(utility.getName(), false, fresh);

            assertThatCode(() -> Class.forName(isolated.getName(), true, fresh)).doesNotThrowAnyException();
            Class<?> modelFactory = Class.forName("org.apache.jena.rdf.model.ModelFactory", true, fresh);
            assertThatCode(() -> modelFactory.getMethod("createDefaultModel").invoke(null))
                    .doesNotThrowAnyException();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static URL[] classpath() throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }
}