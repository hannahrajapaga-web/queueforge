package com.queueforge.queueforge.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {
    List<Job> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Job> findByStatusOrderByUpdatedAtDesc(JobStatus status);
    List<Job> findByStatusAndUpdatedAtBefore(JobStatus status, Instant before);
    long countByStatus(JobStatus status);
    long countByStatusAndUpdatedAtAfter(JobStatus status, Instant after);
}