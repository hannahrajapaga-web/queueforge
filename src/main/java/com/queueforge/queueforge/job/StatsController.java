
package com.queueforge.queueforge.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class StatsController {

    private final JobRepository jobs;
    private final StringRedisTemplate redis;
    private final int workerCount;

    public StatsController(
            JobRepository jobs,
            StringRedisTemplate redis,
            @Value("${worker.count:3}") int workerCount) {
        this.jobs = jobs;
        this.redis = redis;
        this.workerCount = workerCount;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {

        Map<String, Long> byStatus = new LinkedHashMap<>();

        for (JobStatus status : JobStatus.values()) {
            byStatus.put(status.name(), 0L);
        }

        long totalJobs = 0;

        for (Object[] row : jobs.countJobsGroupedByStatus()) {
            JobStatus status = (JobStatus) row[0];
            long count = ((Number) row[1]).longValue();

            byStatus.put(status.name(), count);
            totalJobs += count;
        }

        Map<String, Long> queues = new LinkedHashMap<>();

        queues.put("pending",
                sizeOf(redis.opsForList().size(QueueService.PENDING)));

        queues.put("delayedRetries",
                sizeOf(redis.opsForZSet().zCard(QueueService.DELAYED)));

        queues.put("deadLetter",
                sizeOf(redis.opsForList().size(QueueService.DEAD)));

        long processing = 0;

        for (int i = 1; i <= workerCount; i++) {
            String processingKey =
                    QueueService.PROCESSING_PREFIX + "worker-" + i;

            processing += sizeOf(
                    redis.opsForList().size(processingKey));
        }

        queues.put("processing", processing);

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("totalJobs", totalJobs);
        out.put("jobsByStatus", byStatus);
        out.put("queues", queues);

        out.put("completedLastMinute",
                jobs.countByStatusAndUpdatedAtAfter(
                        JobStatus.COMPLETED,
                        Instant.now().minusSeconds(60)));

        out.put("workers", workerCount);

        return out;
    }

    private static long sizeOf(Long n) {
        return n == null ? 0 : n;
    }
}