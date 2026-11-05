
package com.queueforge.queueforge.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SupabaseStorage {

    private final String baseUrl;
    private final String secretKey;
    private final String bucket;
    private final RestClient client;

    public SupabaseStorage(
            @Value("${supabase.url:}") String url,
            @Value("${supabase.secret-key:}") String secretKey,
            @Value("${supabase.bucket:queueforge}") String bucket) {

        this.baseUrl = url.endsWith("/")
                ? url.substring(0, url.length() - 1)
                : url;

        this.secretKey = secretKey;
        this.bucket = bucket;

        this.client = enabled()
                ? RestClient.builder()
                    .baseUrl(this.baseUrl + "/storage/v1")
                    .defaultHeader("apikey", secretKey)
                    .defaultHeader("Authorization", "Bearer " + secretKey)
                    .build()
                : null;
    }

    public boolean enabled() {
        return !baseUrl.isBlank() && !secretKey.isBlank();
    }

    public static String contentTypeFor(String ext) {
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    public void upload(String key, byte[] bytes, String contentType) {
        client.post()
                .uri("/object/" + bucket + "/" + key)
                .contentType(MediaType.parseMediaType(contentType))
                .header("x-upsert", "true")
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
    }

    public byte[] download(String key) {
        return client.get()
                .uri("/object/" + bucket + "/" + key)
                .retrieve()
                .body(byte[].class);
    }

    public void delete(String key) {
        if (!enabled() || key == null || key.isBlank()) {
            return;
        }

        client.method(HttpMethod.DELETE)
                .uri("/object/" + bucket)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prefixes", List.of(key)))
                .retrieve()
                .toBodilessEntity();
    }

    public String signedUrl(String key, int expiresInSeconds) {
        Map<?, ?> resp = client.post()
                .uri("/object/sign/" + bucket + "/" + key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("expiresIn", expiresInSeconds))
                .retrieve()
                .body(Map.class);

        if (resp == null || resp.get("signedURL") == null) {
            throw new IllegalStateException(
                    "Supabase did not return a signed URL for " + key);
        }

        return baseUrl + "/storage/v1" + resp.get("signedURL");
    }
}
