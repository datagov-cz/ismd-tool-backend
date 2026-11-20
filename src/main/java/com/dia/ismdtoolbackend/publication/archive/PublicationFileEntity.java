package com.dia.ismdtoolbackend.publication.archive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One file of a publication. Name, size and ETag stay forever; {@link #contentGzip} only within the retention. */
@Entity
@Table(name = "publication_file")
@Getter
@Setter
@NoArgsConstructor
public class PublicationFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publication_id", nullable = false)
    private PublicationEntity publication;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "size", nullable = false)
    private long size;

    @Column(name = "etag", nullable = false, length = 80)
    private String etag;

    /** gzip of the served file; null once the publication is older than the retention. */
    @Column(name = "content_gzip", columnDefinition = "bytea")
    private byte[] contentGzip;
}
