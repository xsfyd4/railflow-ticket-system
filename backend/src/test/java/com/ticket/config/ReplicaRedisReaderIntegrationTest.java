package com.ticket.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReplicaRedisReaderIntegrationTest {

    private static final String KEY = "ticket:test:replica-reader";

    @Autowired
    @Qualifier("cacheRedisTemplate")
    private StringRedisTemplate writeRedis;

    @Autowired
    private ReplicaRedisReader reader;

    @Test
    void shouldDiscoverBothReplicasAndReadMasterWrittenValue() {
        writeRedis.opsForValue().set(KEY, "replicated-value");
        try {
            assertEquals(1, reader.replicaTargets().size());
            assertEquals("replicated-value", reader.get(KEY));
            assertEquals("replicated-value", reader.get(KEY));
        } finally {
            writeRedis.delete(KEY);
        }
    }
}
