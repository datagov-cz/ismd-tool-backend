package com.dia.ismdtoolbackend.publication.archive;

/** Where a publication run is: building, stored with its files, or failed without files. */
public enum PublicationStatus {
    RUNNING,
    STORED,
    FAILED
}
