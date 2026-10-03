package com.amitdubey.seats.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fires N requests as close to simultaneously as a single machine can manage.
 *
 * <p>Submitting tasks to a pool is not enough: the first few would finish before the last had
 * started, and a race nobody runs is a race nobody tested. So every task is parked on a
 * {@link CountDownLatch} and released at once, which is what makes contention on a single row
 * actually happen.
 */
public final class Burst {

    private Burst() {
    }

    /** Outcome tally: HTTP status to how many responses carried it. */
    public record Result(Map<Integer, Integer> statuses, List<String> bodies) {

        public int count(int status) {
            return statuses.getOrDefault(status, 0);
        }

        public int serverErrors() {
            return statuses.entrySet().stream()
                    .filter(e -> e.getKey() >= 500)
                    .mapToInt(Map.Entry::getValue)
                    .sum();
        }

        public int total() {
            return statuses.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public String toString() {
            return "statuses=" + statuses;
        }
    }

    /** One request's outcome: its status code and body. */
    public record Response(int status, String body) {
    }

    public static Result fire(int concurrency, Callable<Response> request) {
        // One virtual thread per request. A bounded platform-thread pool would be a trap
        // here: tasks beyond the pool size never start, so the start-gun latch could never
        // reach zero and the burst would deadlock rather than race.
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(concurrency);
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
        List<String> bodies = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    startGun.await();
                    Response response = request.call();
                    statuses.computeIfAbsent(response.status(), s -> new AtomicInteger())
                            .incrementAndGet();
                    bodies.add(response.body() == null ? "" : response.body());
                    return null;
                }));
            }

            if (!ready.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers failed to line up");
            }
            startGun.countDown();

            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("burst failed", e);
        } finally {
            pool.shutdownNow();
        }

        Map<Integer, Integer> tally = new java.util.TreeMap<>();
        statuses.forEach((status, count) -> tally.put(status, count.get()));
        return new Result(tally, bodies);
    }
}
