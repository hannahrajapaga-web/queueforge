
package com.queueforge.queueforge.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Job> findByStatusOrderByUpdatedAtDesc(JobStatus status);

    List<Job> findByStatusAndUpdatedAtBefore(JobStatus status, Instant before);

    long countByStatus(JobStatus status);

    long countByStatusAndUpdatedAtAfter(JobStatus status, Instant after);

    long countByUserId(Long userId);

    @Query("SELECT j.status, COUNT(j) FROM Job j GROUP BY j.status")
    List<Object[]> countJobsGroupedByStatus();
}
