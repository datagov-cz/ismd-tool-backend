package com.dia.ismdtoolbackend.exception;

/**
 * Thrown when a diagram write COMMITTED but the follow-up read of live concept content from Fuseki failed,
 * so the fat response could not be assembled.
 *
 * <p>The distinction from a plain {@link JenaTDB2Exception} is the whole point: the write is durable and the
 * version has advanced. The client must NOT retry the write — a blind retry would send the now-stale version
 * and earn a spurious 409. It should re-issue {@code GET …/detail} to render, using {@link #getVersion()} if
 * it wants to save again without that read first.
 *
 * <p>Diagram writes are pure PG (the layer never writes RDF), so the failing Fuseki call is a READ that
 * happens strictly after every write. Keeping it outside the transaction is what stops a PG connection being
 * held across an external HTTP call; this exception is how that ordering stays honest to the client.
 *
 * <p>See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public class DiagramReadbackFailedException extends RuntimeException {

    /** Stable error code the FE branches on (not the localized message). */
    public static final String ERROR_CODE = "DIAGRAM_SAVED_READBACK_FAILED";

    private final transient Long version;

    public DiagramReadbackFailedException(Long version, Throwable cause) {
        super("Změny diagramu byly uloženy, ale nepodařilo se načíst obsah pojmů pro zobrazení. "
                + "Načtěte diagram znovu; změny zůstávají uložené.", cause);
        this.version = version;
    }

    /** The diagram version AFTER the committed write — echo it in the next layout save. */
    public Long getVersion() {
        return version;
    }
}