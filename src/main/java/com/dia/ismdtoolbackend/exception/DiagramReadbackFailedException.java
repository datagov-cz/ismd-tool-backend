package com.dia.ismdtoolbackend.exception;

/**
 * Thrown when a diagram write committed but the follow-up read of live concept content from Fuseki failed,
 * so the response could not be assembled.
 *
 * <p>Distinct from a plain {@link JenaTDB2Exception} because the write is durable and the version has
 * advanced: the client must not retry the write, which would send the now-stale version and earn a spurious
 * 409. It should re-issue {@code GET …/detail}, or use {@link #getVersion()} to save again without it.
 *
 * <p>Diagram writes are pure PG, so the failing Fuseki call is always a read after the write. Keeping it
 * outside the transaction is what stops a PG connection being held across an external HTTP call. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 */
public class DiagramReadbackFailedException extends RuntimeException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "DIAGRAM_SAVED_READBACK_FAILED";

    private final transient Long version;

    public DiagramReadbackFailedException(Long version, Throwable cause) {
        super("Změny diagramu byly uloženy, ale nepodařilo se načíst obsah pojmů pro zobrazení. "
                + "Načtěte diagram znovu; změny zůstávají uložené.", cause);
        this.version = version;
    }

    /** The diagram version after the committed write; echo it in the next layout save. */
    public Long getVersion() {
        return version;
    }
}