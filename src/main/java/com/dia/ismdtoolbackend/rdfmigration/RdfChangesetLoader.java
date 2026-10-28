package com.dia.ismdtoolbackend.rdfmigration;

import org.apache.jena.update.UpdateFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the changesets from {@code <location>/NNN-name.ru}, sorted by id. Every file is checked before
 * any is applied: the name format, unique ids, the {@code ?graph} variable, and that the update parses.
 */
public final class RdfChangesetLoader {

    /** Where the application's changesets live. */
    public static final String DEFAULT_LOCATION = "classpath*:rdf-migrations";

    private static final Pattern FILE_NAME = Pattern.compile("\\d{3}-[a-z0-9-]+\\.ru");
    private static final String PROBE_GRAPH = "https://example.org/rdf-migration-probe";

    private final ResourcePatternResolver resolver;
    private final String location;

    public RdfChangesetLoader(String location) {
        this(new PathMatchingResourcePatternResolver(), location);
    }

    RdfChangesetLoader(ResourcePatternResolver resolver, String location) {
        this.resolver = resolver;
        this.location = location;
    }

    public List<RdfChangeset> load() {
        Resource[] files;
        try {
            files = resolver.getResources(location + "/*.ru");
        } catch (IOException e) {
            throw new IllegalStateException("RDF migrations: cannot list " + location, e);
        }
        List<RdfChangeset> changesets = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Resource file : files) {
            RdfChangeset changeset = read(file);
            if (!ids.add(changeset.id())) {
                throw new IllegalStateException("RDF migrations: duplicate changeset id " + changeset.id());
            }
            changesets.add(changeset);
        }
        changesets.sort(Comparator.comparing(RdfChangeset::id));
        return List.copyOf(changesets);
    }

    private static RdfChangeset read(Resource file) {
        String name = file.getFilename();
        if (name == null || !FILE_NAME.matcher(name).matches()) {
            throw new IllegalStateException("RDF migrations: file name must be NNN-name.ru (lower case): " + name);
        }
        String id = name.substring(0, name.length() - ".ru".length());
        String text;
        try {
            text = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("RDF migrations: cannot read " + name, e);
        }
        String normalised = (text.startsWith("\uFEFF") ? text.substring(1) : text).replace("\r\n", "\n");
        RdfChangeset changeset = new RdfChangeset(id, description(normalised), normalised, sha256(normalised));
        String parsed;
        String bound;
        try {
            parsed = UpdateFactory.create(normalised).toString();
            bound = changeset.boundTo(PROBE_GRAPH);
        } catch (RuntimeException e) {
            throw new IllegalStateException("RDF migrations: " + id + " is not valid SPARQL Update: " + e.getMessage(), e);
        }
        if (parsed.equals(bound)) {
            throw new IllegalStateException("RDF migrations: " + id + " never uses ?graph");
        }
        return changeset;
    }

    private static String description(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("#")) {
                break;
            }
            lines.add(trimmed.substring(1).strip());
        }
        return String.join(" ", lines).strip();
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
