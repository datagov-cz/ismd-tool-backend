package com.dia.ismdtoolbackend.publication.archive;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PublicationRepository extends JpaRepository<PublicationEntity, Long> {

    /** Ids of the newest stored publications, newest first; running and failed runs are not counted. */
    @Query("""
            select p.id from PublicationEntity p
            where p.status = com.dia.ismdtoolbackend.publication.archive.PublicationStatus.STORED
            order by p.publishedAt desc, p.id desc
            """)
    List<Long> findNewestStoredIds(Pageable pageable);

    /** Marks every {@code RUNNING} run as {@code FAILED} with {@code error}. */
    @Modifying
    @Query("""
            update PublicationEntity p
            set p.status = com.dia.ismdtoolbackend.publication.archive.PublicationStatus.FAILED, p.error = :error
            where p.status = com.dia.ismdtoolbackend.publication.archive.PublicationStatus.RUNNING
            """)
    int failRunning(@Param("error") String error);
}
