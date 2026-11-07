
package com.queueforge.queueforge.job;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class QueueMaintenance {

    private static final Logger log =
            LoggerFactory.getLogger(QueueMaintenance.class);

    private final QueueService queue;
    private final JobRepository jobs;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    public QueueMaintenance(QueueService queue, JobRepository jobs) {
        this.queue = queue;
        this.jobs = jobs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {

        log.info("QUEUE MAINTENANCE STARTED");

        scheduler.scheduleWithFixedDelay(
                this::promote, 1, 1, TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(
                this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    private void promote() {
        try {
            long promoted = queue.promoteDue();

            if (promoted > 0) {
                log.info(
                        "Promoted {} retry job(s) to pending",
                        promoted
                );
            }
        } catch (Exception e) {
            log.error("PROMOTION FAILED", e);
        }
    }

    private void sweep() {
        try {
            Instant cutoff = Instant.now()
                    .minus(Duration.ofMinutes(5));

            for (Job job :
                    jobs.findByStatusAndUpdatedAtBefore(
                            JobStatus.PROCESSING, cutoff)) {

                queue.removeFromProcessing(job.getId());

                job.setStatus(JobStatus.QUEUED);
                jobs.save(job);

                queue.enqueue(job.getId());

                log.warn(
                        "Sweeper requeued stuck job {}",
                        job.getId()
                );
            }
        } catch (Exception e) {
            log.error("SWEEP FAILED", e);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping Queue Maintenance");
        scheduler.shutdownNow();
    }
}
