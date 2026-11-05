
package com.queueforge.queueforge.job;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final StringRedisTemplate redis;
    private final JobRepository jobs;
    private final ImageProcessor processor;
    private final QueueService queue;
    private final int workerCount;
    private final List<Thread> threads = new ArrayList<>();

    private volatile boolean running = true;

    public JobWorker(
            StringRedisTemplate redis,
            JobRepository jobs,
            ImageProcessor processor,
            QueueService queue,
            @Value("${worker.count:3}") int workerCount) {
        this.redis = redis;
        this.jobs = jobs;
        this.processor = processor;
        this.queue = queue;
        this.workerCount = workerCount;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        queue.recoverOrphans();

        for (int i = 1; i <= workerCount; i++) {
            String workerId = "worker-" + i;
            Thread t = new Thread(() -> loop(workerId), workerId);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        log.info("Started {} workers", workerCount);
    }

    @PreDestroy
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
    }

    private void loop(String workerId) {
        String processingKey = QueueService.PROCESSING_PREFIX + workerId;

        while (running) {
            String id = null;
            String lockToken = null;

            try {
                lockToken = queue.acquireQueueLock();

                if (lockToken == null) {
                    Thread.sleep(100);
                    continue;
                }

                id = queue.claimNext(processingKey);

                if (id != null) {
                    Job job = jobs.findById(Long.valueOf(id)).orElse(null);

                    if (job == null) {
                        redis.opsForList().remove(processingKey, 1, id);
                    } else {
                        job.setStatus(JobStatus.PROCESSING);
                        job.setAttempts(job.getAttempts() + 1);
                        jobs.save(job);
                    }

                }

            } catch (InterruptedException e) {
                if (!running) return;
                Thread.currentThread().interrupt();
                return;

            } catch (Exception e) {
                log.error("{} claim error: {}", workerId, e.getMessage());

            } finally {
                queue.releaseQueueLock(lockToken);
            }

            if (id == null) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            Job job = jobs.findById(Long.valueOf(id)).orElse(null);

            if (job != null && job.getStatus() == JobStatus.PROCESSING) {
                handle(workerId, processingKey, job);
            }
        }
    }

    private void handle(String workerId, String processingKey, Job job) {
        Long jobId = job.getId();

        try {
            log.info("{} processing job {} (attempt {})",
                    workerId, jobId, job.getAttempts());

            if (job.getAttempts() <= job.getSimulatedFailures()) {
                throw new IllegalStateException(
                        "Simulated failure on attempt " + job.getAttempts());
            }

            processor.process(job);

            job.setStatus(JobStatus.COMPLETED);
            job.setErrorMessage(null);
            jobs.save(job);

            redis.opsForList().remove(
                    processingKey, 1, String.valueOf(jobId));

            log.info("{} completed job {}", workerId, jobId);

        } catch (Exception e) {
            job.setErrorMessage(String.valueOf(e.getMessage()));

            String outcome = queue.fail(
                    processingKey, jobId, job.getAttempts());

            job.setStatus(
                    "DEAD".equals(outcome)
                            ? JobStatus.FAILED
                            : JobStatus.QUEUED);

            jobs.save(job);

            log.warn("{} job {} failed ({}): {}",
                    workerId, jobId, outcome, e.getMessage());
        }
    }
}
