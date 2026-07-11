/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.tycho.p2tools;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.tycho.ArtifactDescriptor;
import org.eclipse.tycho.ArtifactKey;
import org.eclipse.tycho.ReactorProject;
import org.junit.Test;

/**
 * Unit tests for {@link ArtifactPrefetcher}: the helper that fetches a set of artifacts over a
 * bounded thread pool instead of serially. Drives the package-private
 * {@code prefetch(Collection, Executor)} seam with a controllable executor and latch-gated fake
 * descriptors so concurrency, failure propagation and thread-context handling can be asserted
 * without touching the network.
 */
public class ArtifactPrefetcherTest {

    private static final long TIMEOUT_SECONDS = 5;

    /**
     * The whole point of the change: distinct artifacts must be fetched concurrently, not one after
     * another. Each fake fetch parks on a shared barrier after announcing it has started; if the
     * prefetcher dispatches in parallel all of them announce "started" together and the latch
     * reaches zero. A serial prefetcher would start only the first fetch (which then blocks
     * forever), so the latch never reaches zero and the await times out.
     */
    @Test
    public void prefetchRunsConcurrently() throws Exception {
        int n = 4;
        CountDownLatch allStarted = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        List<ArtifactDescriptor> artifacts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            artifacts.add(new FakeArtifactDescriptor(() -> {
                allStarted.countDown();
                release.await();
                return new File("artifact");
            }));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            ArtifactPrefetcher prefetcher = new ArtifactPrefetcher();
            Future<?> prefetchCall = runner.submit(() -> prefetcher.prefetch(artifacts, pool));

            assertTrue("expected all " + n + " fetches to be in flight concurrently",
                    allStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            release.countDown();
            prefetchCall.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            runner.shutdownNow();
        }
    }

    /**
     * A failed download must not be swallowed: today the serial {@code forEach} lets the unchecked
     * fetch exception (in production, {@code MirroringArtifactProvider.MirroringFailedException})
     * propagate and fail the build, and the parallel version must preserve that. The other fetches
     * still run (no short-circuit), and the original exception instance is rethrown unwrapped.
     */
    @Test
    public void singleFailureIsRethrown() {
        FetchFailure boom = new FetchFailure("boom");
        FakeArtifactDescriptor ok1 = new FakeArtifactDescriptor(() -> new File("a"));
        FakeArtifactDescriptor bad = new FakeArtifactDescriptor(() -> {
            throw boom;
        });
        FakeArtifactDescriptor ok2 = new FakeArtifactDescriptor(() -> new File("b"));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            ArtifactPrefetcher prefetcher = new ArtifactPrefetcher();

            FetchFailure thrown = assertThrows(FetchFailure.class,
                    () -> prefetcher.prefetch(List.of(ok1, bad, ok2), pool));

            assertSame("the original failure must be rethrown unwrapped", boom, thrown);
            assertTrue("sibling fetches must still run (no short-circuit)", ok1.executed && ok2.executed);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Stand-in for the unchecked exception a real fetch throws (e.g. {@code MirroringFailedException}). */
    private static final class FetchFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FetchFailure(String message) {
            super(message);
        }
    }

    /**
     * p2/Equinox code run on a worker thread may rely on the thread-context classloader. The
     * prefetcher must propagate the submitting thread's TCCL onto the pool worker; a plain pool
     * thread would otherwise carry its own (system) classloader.
     */
    @Test
    public void callerTcclPropagatedToWorker() throws Exception {
        ClassLoader marker = new URLClassLoader(new URL[0], getClass().getClassLoader());
        AtomicReference<ClassLoader> seenOnWorker = new AtomicReference<>();
        FakeArtifactDescriptor descriptor = new FakeArtifactDescriptor(() -> {
            seenOnWorker.set(Thread.currentThread().getContextClassLoader());
            return new File("a");
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(marker);
            new ArtifactPrefetcher().prefetch(List.of(descriptor), pool);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            pool.shutdownNow();
        }
        assertSame("worker thread must see the caller's context classloader", marker, seenOnWorker.get());
    }

    /** Minimal {@link ArtifactDescriptor} fake whose fetch behaviour is supplied by the test. */
    private static final class FakeArtifactDescriptor implements ArtifactDescriptor {

        @FunctionalInterface
        interface FetchAction {
            File run() throws Exception;
        }

        private final FetchAction action;
        volatile boolean executed;

        FakeArtifactDescriptor(FetchAction action) {
            this.action = action;
        }

        @Override
        @SuppressWarnings("deprecation")
        public File getLocation(boolean fetch) {
            executed = true;
            try {
                return action.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Optional<File> getLocation() {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<File> fetchArtifact() {
            return CompletableFuture.completedFuture(getLocation(true));
        }

        @Override
        public ArtifactKey getKey() {
            return null;
        }

        @Override
        public ReactorProject getMavenProject() {
            return null;
        }

        @Override
        public String getClassifier() {
            return null;
        }

        @Override
        public Collection<IInstallableUnit> getInstallableUnits() {
            return List.of();
        }
    }
}
