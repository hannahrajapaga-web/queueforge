package com.queueforge.queueforge.job;

import com.queueforge.queueforge.user.User;
import com.queueforge.queueforge.user.UserRepository;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.io.File;
import java.util.Set;

@RestController
@RequestMapping("/api/jobs")
public class JobImageController {

    private static final Set<String> SIZES = Set.of("original", "small", "medium", "large");

    private final JobRepository jobs;
    private final UserRepository users;
    private final ImageProcessor processor;
    private final SupabaseStorage supabase;

    public JobImageController(JobRepository jobs, UserRepository users,
                              ImageProcessor processor, SupabaseStorage supabase) {
        this.jobs = jobs;
        this.users = users;
        this.processor = processor;
        this.supabase = supabase;
    }

    @GetMapping("/{id}/image/{size}")
    public ResponseEntity<Resource> image(@PathVariable Long id, @PathVariable String size,
                                          Authentication auth) {
        if (!SIZES.contains(size)) {
            return ResponseEntity.badRequest().build();
        }
        User user = users.findByUsername(auth.getName()).orElseThrow();
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        Job job = jobs.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (!admin && !job.getUserId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String ext = StorageService.extensionOf(job.getStoredPath());
        MediaType type = switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_JPEG;
        };

        if (supabase.enabled()) {
            String key = size.equals("original")
                    ? job.getStoredPath()
                    : ImageProcessor.processedKey(id, size, ext);
            try {
                byte[] bytes = supabase.download(key);
                if (bytes == null || bytes.length == 0) {
                    return ResponseEntity.notFound().build();
                }
                return ResponseEntity.ok().contentType(type).body(new ByteArrayResource(bytes));
            } catch (RestClientResponseException e) {
                // Supabase answers 400 or 404 when the object does not exist (for example, job not processed yet)
                if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 404) {
                    return ResponseEntity.notFound().build();
                }
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
        }

        File file = size.equals("original")
                ? new File(job.getStoredPath())
                : processor.outputDirFor(id).resolve(size + "." + ext).toFile();
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
    }
}