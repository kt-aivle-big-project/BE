package com.aivle.be.boardpost.repository;

import com.aivle.be.boardpost.entity.BoardPost;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoardPostRepository extends JpaRepository<BoardPost, Long> {

    @EntityGraph(attributePaths = "author")
    List<BoardPost> findAllByOrderByCreatedAtDescIdDesc();

    @Override
    @EntityGraph(attributePaths = "author")
    Optional<BoardPost> findById(Long postId);
}
