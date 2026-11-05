
package com.queueforge.queueforge.job;

import com.queueforge.queueforge.user.User;
import com.queueforge.queueforge.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record JobResponse(
            Long id,
            String originalFilename,
            JobStatus status,
            int attempts,
            String errorMessage,
            Instant createdAt) {

        public static JobResponse of(Job j) {
            return new JobResponse(
                    j.getId(),
                    j.getOriginalFilename(),
                    j.getStatus(),
                    j.getAttempts(),
                    j.getErrorMessage(),
                    j.getCreatedAt());
        }
    }

    private final JobRepository jobs;
    private final UserRepository users;
    private final StorageService storage;
    private final QueueService queue;
    private final ImageProcessor processor;

    public JobController(
            JobRepository jobs,
            UserRepository users,
            StorageService storage,
            QueueService queue,
            ImageProcessor processor) {

        this.jobs = jobs;
        this.users = users;
        this.storage = storage;
        this.queue = queue;
        this.processor = processor;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "simulateFailures", defaultValue = "0") int simulateFailures,
            Authentication auth) throws IOException {

        if (files.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No files uploaded"));
        }

        for (MultipartFile f : files) {
            if (!storage.isAllowed(f)) {
                return ResponseEntity.badRequest().body(
                        Map.of("error",
                                "Only jpg, jpeg, png and webp images are allowed: "
                                        + f.getOriginalFilename()));
            }
        }

        User user = users.findByUsername(auth.getName()).orElseThrow();

        List<JobResponse> created = new ArrayList<>();

        for (MultipartFile f : files) {
            Job job = new Job();
            job.setUserId(user.getId());
            job.setOriginalFilename(f.getOriginalFilename());
            job.setStoredPath(storage.saveOriginal(f));
            job.setSimulatedFailures(simulateFailures);

            job = jobs.save(job);
            queue.enqueue(job.getId());

            created.add(JobResponse.of(job));
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    @GetMapping
    public List<JobResponse> myJobs(Authentication auth) {
        User user = users.findByUsername(auth.getName()).orElseThrow();

        List<JobResponse> result = new ArrayList<>();

        for (Job job : jobs.findByUserIdOrderByCreatedAtDesc(user.getId())) {
            result.add(JobResponse.of(job));
        }

        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> one(
            @PathVariable Long id,
            Authentication auth) {

        User user = users.findByUsername(auth.getName()).orElseThrow();

        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        Job job = jobs.findById(id).orElse(null);

        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Job not found"));
        }

        if (!admin && !job.getUserId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Not your job"));
        }

        return ResponseEntity.ok(JobResponse.of(job));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteJob(
            @PathVariable Long id,
            Authentication auth) {

        User user = users.findByUsername(auth.getName()).orElseThrow();

        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        // Acquire the Redis queue lock.
        String lockToken = queue.acquireQueueLockWithRetry();

        if (lockToken == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error",
                            "Queue is busy. Please try again."));
        }

        // Automatically renew the lock while deletion is running.
        ScheduledFuture<?> renewal =
                queue.startQueueLockRenewal(lockToken);

        try {
            // Fetch the latest job state after acquiring the lock.
            Job job = jobs.findById(id).orElse(null);

            if (job == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Job not found"));
            }

            // Check job ownership.
            if (!admin && !job.getUserId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not your job"));
            }

            // Only COMPLETED and FAILED jobs can be deleted.
            if (job.getStatus() != JobStatus.COMPLETED
                    && job.getStatus() != JobStatus.FAILED) {

                String errorMessage =
                        job.getStatus() == JobStatus.QUEUED
                                ? "Queued jobs cannot be deleted."
                                : "Processing jobs cannot be deleted.";

                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", errorMessage));
            }

            // Extra protection against jobs still in processing queues.
            if (queue.isProcessing(id)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error",
                                "This job is currently processing and cannot be deleted."));
            }

            // Delete processed images first, then the original.
            processor.deleteProcessed(id, job.getStoredPath());
            storage.deleteOriginal(job.getStoredPath());

            // Remove the job from Redis queues.
            queue.removeFromQueues(id);

            // Finally delete the database record.
            jobs.delete(job);

            return ResponseEntity.ok(
                    Map.of("message", "Job deleted successfully"));

        } catch (IOException | RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error",
                            "Could not completely delete the job. Please try again."));

        } finally {
            // Stop renewal before releasing the lock.
            queue.stopQueueLockRenewal(renewal);
            queue.releaseQueueLock(lockToken);
        }
    }
}
