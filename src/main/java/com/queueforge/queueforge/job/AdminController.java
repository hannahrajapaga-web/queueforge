package com.queueforge.queueforge.job;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JobRepository jobs;
    private final QueueService queue;

    public AdminController(JobRepository jobs, QueueService queue) {
        this.jobs = jobs;
        this.queue = queue;
    }

    @GetMapping("/dead-letter")
    public List<JobController.JobResponse> deadLetter() {
        List<JobController.JobResponse> result = new ArrayList<>();
        for (Job job : jobs.findByStatusOrderByUpdatedAtDesc(JobStatus.FAILED)) {
            result.add(JobController.JobResponse.of(job));
        }
        return result;
    }

    @PostMapping("/dead-letter/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id) {
        Job job = jobs.findById(id).orElse(null);
        if (job == null || job.getStatus() != JobStatus.FAILED) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a failed job"));
        }
        job.setAttempts(0);
        job.setSimulatedFailures(0);
        job.setErrorMessage(null);
        job.setStatus(JobStatus.QUEUED);
        jobs.save(job);
        queue.retryFromDead(id);
        return ResponseEntity.ok(JobController.JobResponse.of(job));
    }
}