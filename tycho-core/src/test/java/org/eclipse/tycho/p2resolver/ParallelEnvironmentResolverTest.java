/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.tycho.p2resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.tycho.TargetEnvironment;
import org.junit.Test;

public class ParallelEnvironmentResolverTest {

    private static List<TargetEnvironment> environments(int n) {
        List<TargetEnvironment> envs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            envs.add(new TargetEnvironment("os" + i, "ws" + i, "arch" + i));
        }
        return envs;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            assertTrue("timed out waiting on latch", latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Teeth: with an N-thread executor all N per-environment solves must be in flight simultaneously. A serial
     * implementation only ever starts one solve (which blocks), so the "all started" latch never reaches zero.
     */
    @Test(timeout = 30000)
    public void runsConcurrently() throws Exception {
        int n = 4;
        List<TargetEnvironment> envs = environments(n);
        CountDownLatch allStarted = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        ExecutorService driver = Executors.newSingleThreadExecutor();
        try {
            Function<TargetEnvironment, Integer> solve = env -> {
                allStarted.countDown();
                awaitUninterruptibly(release);
                return 1;
            };
            // resolve() blocks until all solves finish, so drive it from a separate thread
            Future<Map<TargetEnvironment, Integer>> result = driver
                    .submit(() -> ParallelEnvironmentResolver.resolve(envs, solve, pool));

            assertTrue("expected all " + n + " solves to run concurrently",
                    allStarted.await(10, TimeUnit.SECONDS));
            release.countDown();

            Map<TargetEnvironment, Integer> map = result.get(10, TimeUnit.SECONDS);
            assertEquals(n, map.size());
        } finally {
            release.countDown();
            pool.shutdownNow();
            driver.shutdownNow();
        }
    }

    /**
     * The result map must iterate in the same order as the input environments, even when individual solves
     * complete out of order. Guards against using an unordered (e.g. ConcurrentHashMap) result map.
     */
    @Test(timeout = 30000)
    public void preservesEnvironmentOrder() {
        int n = 6;
        List<TargetEnvironment> envs = environments(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            Function<TargetEnvironment, Integer> solve = env -> {
                // make later environments finish first to scramble completion order
                int idx = Integer.parseInt(env.getOs().substring(2));
                try {
                    Thread.sleep((n - idx) * 15L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return idx;
            };
            Map<TargetEnvironment, Integer> map = ParallelEnvironmentResolver.resolve(envs, solve, pool);
            assertEquals(envs, new ArrayList<>(map.keySet()));
            assertEquals(envs.get(0), new ArrayList<>(map.keySet()).get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * If one environment's solve fails, the original (unwrapped) exception must propagate - matching the
     * previous serial loop, where a failed solve aborted resolution. Guards against leaking a CompletionException.
     */
    @Test(timeout = 30000)
    public void failurePropagates() {
        List<TargetEnvironment> envs = environments(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Function<TargetEnvironment, Integer> solve = env -> {
                if (env.getOs().equals("os1")) {
                    throw new IllegalStateException("boom");
                }
                return 1;
            };
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ParallelEnvironmentResolver.resolve(envs, solve, pool));
            assertEquals("boom", ex.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Worker threads must run each solve with the submitting thread's context classloader (p2/Equinox code on
     * the worker may rely on the TCCL).
     */
    @Test(timeout = 30000)
    public void tcclPropagated() {
        Thread current = Thread.currentThread();
        ClassLoader original = current.getContextClassLoader();
        ClassLoader marker = new URLClassLoader(new URL[0], original);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            current.setContextClassLoader(marker);
            List<TargetEnvironment> envs = environments(3);
            Map<TargetEnvironment, ClassLoader> seen = ParallelEnvironmentResolver.resolve(envs,
                    env -> Thread.currentThread().getContextClassLoader(), pool);
            assertEquals(3, seen.size());
            for (ClassLoader cl : seen.values()) {
                assertSame("worker should see the submitter's context classloader", marker, cl);
            }
        } finally {
            current.setContextClassLoader(original);
            pool.shutdownNow();
        }
    }
}
