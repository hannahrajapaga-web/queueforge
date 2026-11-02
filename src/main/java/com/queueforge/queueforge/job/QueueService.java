package com.queueforge.queueforge.job;

import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class QueueService {

    public static final String PENDING = "queue:pending";
    public static final String DELAYED = "queue:delayed";
    public static final String DEAD = "queue:dead";
    public static final String PROCESSING_PREFIX = "queue:processing:";
    public static final int MAX_ATTEMPTS = 3;
    public static final long BASE_DELAY_MS = 2000;

    // Atomic: remove from processing list, then either schedule a retry or send to the dead-letter queue
    private static final DefaultRedisScript<String> FAIL_SCRIPT = new DefaultRedisScript<>("""
            redis.call('LREM', KEYS[1], 1, ARGV[1])
            local attempts = tonumber(ARGV[2])
            local max = tonumber(ARGV[3])
            if attempts >= max then
              redis.call('LPUSH', KEYS[3], ARGV[1])
              return 'DEAD'
            end
            local delay = tonumber(ARGV[5]) * (2 ^ (attempts - 1))
            redis.call('ZADD', KEYS[2], string.format('%.0f', tonumber(ARGV[4]) + delay), ARGV[1])
            return 'RETRY'
            """, String.class);

    // Atomic: move every retry whose time has come from the delayed set to the pending queue
    private static final DefaultRedisScript<Long> PROMOTE_SCRIPT = new DefaultRedisScript<>("""
            local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 100)
            for _, id in ipairs(ids) do
              redis.call('ZREM', KEYS[1], id)
              redis.call('LPUSH', KEYS[2], id)
            end
            return #ids
            """, Long.class);

    private final StringRedisTemplate redis;

    public QueueService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void enqueue(Long jobId) {
        redis.opsForList().leftPush(PENDING, String.valueOf(jobId));
    }

    /** Returns "RETRY" or "DEAD". */
    public String fail(String processingKey, Long jobId, int attempts) {
        return redis.execute(FAIL_SCRIPT,
                List.of(processingKey, DELAYED, DEAD),
                String.valueOf(jobId), String.valueOf(attempts), String.valueOf(MAX_ATTEMPTS),
                String.valueOf(System.currentTimeMillis()), String.valueOf(BASE_DELAY_MS));
    }

    public long promoteDue() {
        Long n = redis.execute(PROMOTE_SCRIPT, List.of(DELAYED, PENDING),
                String.valueOf(System.currentTimeMillis()));
        return n == null ? 0 : n;
    }

    /** On startup: anything left in a processing list belongs to a worker that died. Put it back. */
    public void recoverOrphans() {
        Set<String> keys = redis.keys(PROCESSING_PREFIX + "*");
        if (keys == null) return;
        for (String key : keys) {
            while (redis.opsForList().move(key, Direction.RIGHT, PENDING, Direction.LEFT) != null) {
                // keep moving until the list is empty
            }
        }
    }

    public void removeFromProcessing(Long jobId) {
        Set<String> keys = redis.keys(PROCESSING_PREFIX + "*");
        if (keys == null) return;
        for (String key : keys) {
            redis.opsForList().remove(key, 0, String.valueOf(jobId));
        }
    }

    public void retryFromDead(Long jobId) {
        redis.opsForList().remove(DEAD, 0, String.valueOf(jobId));
        enqueue(jobId);
    }
}