
package com.queueforge.queueforge.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class StorageService {

    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "webp");

    private final Path originalsDir;
    private final SupabaseStorage supabase;

    public StorageService(@Value("${storage.dir:uploads}") String baseDir,
                          SupabaseStorage supabase) throws IOException {
        this.supabase = supabase;
        this.originalsDir = Paths.get(baseDir, "originals").toAbsolutePath().normalize();

        if (!supabase.enabled()) {
            Files.createDirectories(originalsDir);
        }
    }

    public static String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    public boolean isAllowed(MultipartFile file) {
        String type = file.getContentType();
        return !file.isEmpty()
                && type != null && type.startsWith("image/")
                && ALLOWED.contains(extensionOf(file.getOriginalFilename()));
    }

    public String saveOriginal(MultipartFile file) throws IOException {
        String ext = extensionOf(file.getOriginalFilename());
        String name = UUID.randomUUID() + "." + ext;

        if (supabase.enabled()) {
            String key = "originals/" + name;
            supabase.upload(key, file.getBytes(), SupabaseStorage.contentTypeFor(ext));
            return key;
        }

        Path target = originalsDir.resolve(name);
        file.transferTo(target);
        return target.toString();
    }

    public void deleteOriginal(String storedPath) throws IOException {
        if (storedPath == null || storedPath.isBlank()) return;

        if (supabase.enabled()) {
            supabase.delete(storedPath);
            return;
        }

        Path file = Paths.get(storedPath).toAbsolutePath().normalize();

        if (!file.startsWith(originalsDir)) {
            throw new IOException("Invalid original image path");
        }

        Files.deleteIfExists(file);
    }
}
