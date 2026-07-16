package com.aivle.be.event.repository;

import com.aivle.be.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}