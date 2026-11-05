
package com.queueforge.queueforge.job;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class QueueService {

    private static final Logger log =
            LoggerFactory.getLogger(QueueService.class);

    public static final String PENDING = "queue:pending";
    public static final String DELAYED = "queue:delayed";
    public static final String DEAD = "queue:dead";
    public static final String PROCESSING_PREFIX = "queue:processing:";

    public static final int MAX_ATTEMPTS = 3;
    public static final long BASE_DELAY_MS = 2000;

    private static final String QUEUE_LOCK = "queue:operation:lock";

    private static final Duration LOCK_DURATION =
            Duration.ofSeconds(30);

    private static final long LOCK_RENEWAL_SECONDS = 10;

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                      return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_LOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                      return redis.call('PEXPIRE', KEYS[1], ARGV[2])
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<String> FAIL_SCRIPT =
            new DefaultRedisScript<>("""
                    redis.call('LREM', KEYS[1], 1, ARGV[1])
                    local attempts = tonumber(ARGV[2])
                    local max = tonumber(ARGV[3])
                    if attempts >= max then
                      redis.call('LPUSH', KEYS[3], ARGV[1])
                      return 'DEAD'
                    end
                    local delay = tonumber(ARGV[5]) * (2 ^ (attempts - 1))
                    redis.call('ZADD', KEYS[2],
                      string.format('%.0f', tonumber(ARGV[4]) + delay), ARGV[1])
                    return 'RETRY'
                    """, String.class);

    private static final DefaultRedisScript<Long> PROMOTE_SCRIPT =
            new DefaultRedisScript<>("""
                    local ids = redis.call('ZRANGEBYSCORE',
                      KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 100)
                    for _, id in ipairs(ids) do
                      redis.call('ZREM', KEYS[1], id)
                      redis.call('LPUSH', KEYS[2], id)
                    end
                    return #ids
                    """, Long.class);

    private final StringRedisTemplate redis;

    private final ScheduledExecutorService lockScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "queue-lock-renewer");
                thread.setDaemon(true);
                return thread;
            });

    public QueueService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void enqueue(Long jobId) {
        redis.opsForList().leftPush(
                PENDING, String.valueOf(jobId));
    }

    public String acquireQueueLock() {

        String token = UUID.randomUUID().toString();

        Boolean acquired = redis.opsForValue().setIfAbsent(
                QUEUE_LOCK, token, LOCK_DURATION);

        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public String acquireQueueLockWithRetry() {

        for (int i = 0; i < 10; i++) {

            String token = acquireQueueLock();

            if (token != null) {
                return token;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;
    }

    // Automatically renews the lock while a protected operation runs.
    public ScheduledFuture<?> startQueueLockRenewal(String token) {

        return lockScheduler.scheduleAtFixedRate(() -> {

            try {
                Long renewed = redis.execute(
                        RENEW_LOCK_SCRIPT,
                        List.of(QUEUE_LOCK),
                        token,
                        String.valueOf(LOCK_DURATION.toMillis())
                );

                if (!Long.valueOf(1).equals(renewed)) {
                    log.error("Queue lock renewal failed: lock ownership lost.");
                }

            } catch (Exception e) {
                log.error("Could not renew queue lock: {}", e.getMessage());
            }

        }, LOCK_RENEWAL_SECONDS,
                LOCK_RENEWAL_SECONDS,
                TimeUnit.SECONDS);
    }

    public void releaseQueueLock(String token) {

        if (token != null) {
            redis.execute(
                    RELEASE_LOCK_SCRIPT,
                    List.of(QUEUE_LOCK),
                    token);
        }
    }

    public void stopQueueLockRenewal(ScheduledFuture<?> renewal) {

        if (renewal != null) {
            renewal.cancel(false);
        }
    }

    public String claimNext(String processingKey) {

        return redis.opsForList().rightPopAndLeftPush(
                PENDING, processingKey);
    }

    public boolean isProcessing(Long jobId) {

        Set<String> keys = redis.keys(PROCESSING_PREFIX + "*");

        if (keys == null) {
            return false;
        }

        String id = String.valueOf(jobId);

        for (String key : keys) {

            List<String> jobs = redis.opsForList().range(key, 0, -1);

            if (jobs != null && jobs.contains(id)) {
                return true;
            }
        }

        return false;
    }

    public void removeFromQueues(Long jobId) {

        String id = String.valueOf(jobId);

        redis.opsForList().remove(PENDING, 0, id);
        redis.opsForList().remove(DEAD, 0, id);
        redis.opsForZSet().remove(DELAYED, id);
    }

    public String fail(
            String processingKey,
            Long jobId,
            int attempts) {

        return redis.execute(
                FAIL_SCRIPT,
                List.of(processingKey, DELAYED, DEAD),
                String.valueOf(jobId),
                String.valueOf(attempts),
                String.valueOf(MAX_ATTEMPTS),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(BASE_DELAY_MS));
    }

    public long promoteDue() {

        Long n = redis.execute(
                PROMOTE_SCRIPT,
                List.of(DELAYED, PENDING),
                String.valueOf(System.currentTimeMillis()));

        return n == null ? 0 : n;
    }

    public void recoverOrphans() {

        Set<String> keys = redis.keys(PROCESSING_PREFIX + "*");

        if (keys == null) {
            return;
        }

        for (String key : keys) {

            while (redis.opsForList().move(
                    key,
                    Direction.RIGHT,
                    PENDING,
                    Direction.LEFT) != null) {
                // Move remaining jobs back to pending.
            }
        }
    }

    public void removeFromProcessing(Long jobId) {

        Set<String> keys = redis.keys(PROCESSING_PREFIX + "*");

        if (keys == null) {
            return;
        }

        for (String key : keys) {
            redis.opsForList().remove(
                    key, 0, String.valueOf(jobId));
        }
    }

    public void retryFromDead(Long jobId) {

        redis.opsForList().remove(
                DEAD, 0, String.valueOf(jobId));

        enqueue(jobId);
    }

    @PreDestroy
    public void shutdown() {
        lockScheduler.shutdownNow();
    }
}
