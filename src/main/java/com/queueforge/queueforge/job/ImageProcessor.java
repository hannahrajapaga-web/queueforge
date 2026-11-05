
package com.queueforge.queueforge.job;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ImageProcessor {

    private static final Map<String, Integer> SIZES = new LinkedHashMap<>();

    static {
        SIZES.put("small", 150);
        SIZES.put("medium", 600);
        SIZES.put("large", 1200);
    }

    private final Path processedDir;
    private final SupabaseStorage supabase;

    public ImageProcessor(
            @Value("${storage.dir:uploads}") String baseDir,
            SupabaseStorage supabase) {

        this.supabase = supabase;
        this.processedDir = Paths.get(baseDir, "processed")
                .toAbsolutePath()
                .normalize();
    }

    /** Local folder for a job's processed images. */
    public Path outputDirFor(Long jobId) {
        return processedDir.resolve(String.valueOf(jobId));
    }

    /** Supabase key for one processed image. */
    public static String processedKey(Long jobId, String size, String ext) {
        return "processed/" + jobId + "/" + size + "." + ext;
    }

    public static Iterable<String> sizeNames() {
        return SIZES.keySet();
    }

    public void process(Job job) throws IOException {
        if (supabase.enabled()) {
            processInSupabase(job);
        } else {
            processOnDisk(job);
        }
    }

    private void processOnDisk(Job job) throws IOException {
        File source = new File(job.getStoredPath());
        String ext = StorageService.extensionOf(source.getName());

        Path outDir = outputDirFor(job.getId());
        Files.createDirectories(outDir);

        for (Map.Entry<String, Integer> size : SIZES.entrySet()) {
            File target = outDir.resolve(
                    size.getKey() + "." + ext).toFile();

            Thumbnails.of(source)
                    .size(size.getValue(), size.getValue())
                    .toFile(target);
        }
    }

    private void processInSupabase(Job job) throws IOException {
        String key = job.getStoredPath();
        String ext = StorageService.extensionOf(key);

        byte[] original = supabase.download(key);

        if (original == null || original.length == 0) {
            throw new IOException(
                    "Original image not found in storage: " + key);
        }

        String format = ext.equals("jpeg") ? "jpg" : ext;

        for (Map.Entry<String, Integer> size : SIZES.entrySet()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            Thumbnails.of(new ByteArrayInputStream(original))
                    .size(size.getValue(), size.getValue())
                    .outputFormat(format)
                    .toOutputStream(out);

            supabase.upload(
                    processedKey(job.getId(), size.getKey(), ext),
                    out.toByteArray(),
                    SupabaseStorage.contentTypeFor(ext));
        }
    }

    /** Deletes all processed images belonging to a job. */
    public void deleteProcessed(Long jobId, String storedPath)
            throws IOException {

        String ext = StorageService.extensionOf(storedPath);

        if (supabase.enabled()) {
            for (String size : SIZES.keySet()) {
                supabase.delete(
                        processedKey(jobId, size, ext));
            }
            return;
        }

        Path jobDir = outputDirFor(jobId)
                .toAbsolutePath()
                .normalize();

        if (!jobDir.startsWith(processedDir)) {
            throw new IOException("Invalid processed image path");
        }

        if (Files.exists(jobDir)) {
            try (var files = Files.list(jobDir)) {
                for (Path file : files.toList()) {
                    if (Files.isRegularFile(file)) {
                        Files.deleteIfExists(file);
                    }
                }
            }

            Files.deleteIfExists(jobDir);
        }
    }
}
