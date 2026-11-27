package com.dia.ismdtoolbackend.publication.archive;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One publication run, kept forever as the audit trail; it has files once it is {@code STORED}. */
@Entity
@Table(name = "publication")
@Getter
@Setter
@NoArgsConstructor
public class PublicationEntity {

    public static final int ERROR_LENGTH = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "published_at", nullable = false, columnDefinition = "timestamptz")
    private Instant publishedAt;

    @Column(name = "triggered_by", nullable = false)
    private String triggeredBy;

    @Column(name = "include_drafts", nullable = false)
    private boolean includeDrafts;

    @Column(name = "complete", nullable = false)
    private boolean complete;

    @Column(name = "vocabularies", nullable = false)
    private int vocabularies;

    @Column(name = "diagrams", nullable = false)
    private int diagrams;

    @Column(name = "context_url", nullable = false, length = 1024)
    private String contextUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PublicationStatus status = PublicationStatus.RUNNING;

    /** Why a {@code FAILED} run failed; null otherwise. */
    @Column(name = "error", length = ERROR_LENGTH)
    private String error;

    @OneToMany(mappedBy = "publication", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PublicationFileEntity> files = new ArrayList<>();

    public void addFile(PublicationFileEntity file) {
        file.setPublication(this);
        files.add(file);
    }
}
