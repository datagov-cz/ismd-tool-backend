package com.dia.ismdtoolbackend.publication.archive;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PublicationRepository extends JpaRepository<PublicationEntity, Long> {

    /** Ids of the newest publications, newest first. */
    @Query("select p.id from PublicationEntity p order by p.publishedAt desc, p.id desc")
    List<Long> findNewestIds(Pageable pageable);
}
