package com.seojs.aisenpai_backend.pullrequest.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyLimitTest {

    @Test
    void testSequentialThrottling() throws InterruptedException {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch2 = new CountDownLatch(2);

        CodeGraphIndexService service = new CodeGraphIndexService(null, null, null, null, null) {
            @Override
            public void runIndexing(Long repositoryId, String refName, String commitSha, boolean defaultBranch) throws Exception {
                executionOrder.add(commitSha + "-start");
                Thread.sleep(100);
                executionOrder.add(commitSha + "-end");
                latch2.countDown();
            }
        };

        ReflectionTestUtils.setField(service, "indexTimeoutSeconds", 30);

        service.submitIndexingTask(1L, "refs/heads/main", "sha1", true);
        service.submitIndexingTask(1L, "refs/heads/main", "sha2", true);

        boolean completed = latch2.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Tasks did not complete in time");

        assertEquals(4, executionOrder.size());
        assertEquals("sha1-start", executionOrder.get(0));
        assertEquals("sha1-end", executionOrder.get(1));
        assertEquals("sha2-start", executionOrder.get(2));
        assertEquals("sha2-end", executionOrder.get(3));
    }
}
