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

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record JobResponse(Long id, String originalFilename, JobStatus status,
                              int attempts, String errorMessage, Instant createdAt) {
        public static JobResponse of(Job j) {
            return new JobResponse(j.getId(), j.getOriginalFilename(), j.getStatus(),
                    j.getAttempts(), j.getErrorMessage(), j.getCreatedAt());
        }
    }

    private final JobRepository jobs;
    private final UserRepository users;
    private final StorageService storage;
    private final QueueService queue;

    public JobController(JobRepository jobs, UserRepository users,
                         StorageService storage, QueueService queue) {
        this.jobs = jobs;
        this.users = users;
        this.storage = storage;
        this.queue = queue;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("files") List<MultipartFile> files,
                                    @RequestParam(value = "simulateFailures", defaultValue = "0") int simulateFailures,
                                    Authentication auth) throws IOException {
        if (files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No files uploaded"));
        }
        for (MultipartFile f : files) {
            if (!storage.isAllowed(f)) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Only jpg, jpeg, png and webp images are allowed: " + f.getOriginalFilename()));
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
    public ResponseEntity<?> one(@PathVariable Long id, Authentication auth) {
        User user = users.findByUsername(auth.getName()).orElseThrow();
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        Job job = jobs.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found"));
        }
        if (!admin && !job.getUserId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Not your job"));
        }
        return ResponseEntity.ok(JobResponse.of(job));
    }
}