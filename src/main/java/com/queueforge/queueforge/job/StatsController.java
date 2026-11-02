package com.queueforge.queueforge.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
public class StatsController {

    private final JobRepository jobs;
    private final StringRedisTemplate redis;
    private final int workerCount;

    public StatsController(JobRepository jobs, StringRedisTemplate redis,
                           @Value("${worker.count:3}") int workerCount) {
        this.jobs = jobs;
        this.redis = redis;
        this.workerCount = workerCount;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (JobStatus s : JobStatus.values()) {
            byStatus.put(s.name(), jobs.countByStatus(s));
        }

        Map<String, Long> queues = new LinkedHashMap<>();
        queues.put("pending", sizeOf(redis.opsForList().size(QueueService.PENDING)));
        queues.put("delayedRetries", sizeOf(redis.opsForZSet().zCard(QueueService.DELAYED)));
        queues.put("deadLetter", sizeOf(redis.opsForList().size(QueueService.DEAD)));

        long processing = 0;
        Set<String> keys = redis.keys(QueueService.PROCESSING_PREFIX + "*");
        if (keys != null) {
            for (String key : keys) {
                processing += sizeOf(redis.opsForList().size(key));
            }
        }
        queues.put("processing", processing);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalJobs", jobs.count());
        out.put("jobsByStatus", byStatus);
        out.put("queues", queues);
        out.put("completedLastMinute",
                jobs.countByStatusAndUpdatedAtAfter(JobStatus.COMPLETED, Instant.now().minusSeconds(60)));
        out.put("workers", workerCount);
        return out;
    }

    private static long sizeOf(Long n) {
        return n == null ? 0 : n;
    }
}