package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<CommentEntity, Long> {
}
