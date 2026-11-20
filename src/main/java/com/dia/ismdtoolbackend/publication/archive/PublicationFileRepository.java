package com.dia.ismdtoolbackend.publication.archive;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublicationFileRepository extends JpaRepository<PublicationFileEntity, Long> {

    Optional<PublicationFileEntity> findByPublicationIdAndName(Long publicationId, String name);

    /** The files of {@code publicationIds} without loading their content. */
    @Query("""
            select new com.dia.ismdtoolbackend.publication.archive.PublicationFileSummaryRow(
                f.publication.id, f.name, f.size, f.etag,
                case when f.contentGzip is null then false else true end)
            from PublicationFileEntity f
            where f.publication.id in :publicationIds
            order by f.name
            """)
    List<PublicationFileSummaryRow> findSummaries(@Param("publicationIds") Collection<Long> publicationIds);

    /** Drops the content of every file outside {@code keep}; the file rows stay as the audit trail. */
    @Modifying
    @Query("""
            update PublicationFileEntity f set f.contentGzip = null
            where f.contentGzip is not null and f.publication.id not in :keep
            """)
    int clearContentExcept(@Param("keep") Collection<Long> keep);
}
