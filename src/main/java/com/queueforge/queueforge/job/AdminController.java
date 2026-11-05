
package com.queueforge.queueforge.job;

import com.queueforge.queueforge.user.User;
import com.queueforge.queueforge.user.UserRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JobRepository jobs;
    private final QueueService queue;
    private final UserRepository users;

    public AdminController(JobRepository jobs, QueueService queue,
                           UserRepository users) {
        this.jobs = jobs;
        this.queue = queue;
        this.users = users;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> getAllUsers() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (User user : users.findAll()) {
            Map<String, Object> details = new LinkedHashMap<>();

            details.put("id", user.getId());
            details.put("username", user.getUsername());
            details.put("email", user.getEmail());
            details.put("role", user.getRole());
            details.put("createdAt", user.getCreatedAt());
            details.put("totalJobs", jobs.countByUserId(user.getId()));

            result.add(details);
        }

        return result;
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
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Not a failed job"));
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
