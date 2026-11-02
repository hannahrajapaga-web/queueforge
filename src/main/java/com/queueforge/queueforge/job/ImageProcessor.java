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

    public ImageProcessor(@Value("${storage.dir:uploads}") String baseDir,
                          SupabaseStorage supabase) {
        this.supabase = supabase;
        this.processedDir = Paths.get(baseDir, "processed").toAbsolutePath().normalize();
    }

    /** Local folder for a job's processed images (used only when Supabase is not configured). */
    public Path outputDirFor(Long jobId) {
        return processedDir.resolve(String.valueOf(jobId));
    }

    /** Bucket key for one processed size, e.g. "processed/12/small.png". */
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
            File target = outDir.resolve(size.getKey() + "." + ext).toFile();
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
            throw new IOException("Original image not found in storage: " + key);
        }
        String format = ext.equals("jpeg") ? "jpg" : ext;
        for (Map.Entry<String, Integer> size : SIZES.entrySet()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(original))
                    .size(size.getValue(), size.getValue())
                    .outputFormat(format)
                    .toOutputStream(out);
            supabase.upload(processedKey(job.getId(), size.getKey(), ext),
                    out.toByteArray(), SupabaseStorage.contentTypeFor(ext));
        }
    }
}