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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.tycho.MavenRepositoryLocation;
import org.junit.Test;

/**
 * Unit tests for {@link MetadataRepositoryPrewarmer}: the helper that loads the top-level metadata
 * repositories of a target platform concurrently (so their content/artifacts indices download in
 * parallel) before the existing sequential walk re-reads them from the p2 manager's cache. Drives
 * the helper with a mocked {@link IMetadataRepositoryManager} and a controllable executor so
 * concurrency, failure handling and thread-context behaviour can be asserted without the network.
 */
public class MetadataRepositoryPrewarmerTest {

    private static final long TIMEOUT_SECONDS = 5;

    private static List<MavenRepositoryLocation> locations(int n) {
        List<MavenRepositoryLocation> locations = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            locations.add(new MavenRepositoryLocation("r" + i, URI.create("https://example.org/repo" + i + "/")));
        }
        return locations;
    }

    /**
     * The point of the change: the top-level repositories must be loaded concurrently. Each fake load
     * announces it has started then parks on a barrier; if pre-warming dispatches in parallel all of
     * them start together and the latch reaches zero, whereas a serial pre-warm would start only the
     * first (which blocks) and the await would time out.
     */
    @Test
    public void prewarmLoadsConcurrently() throws Exception {
        int n = 4;
        CountDownLatch allStarted = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        IMetadataRepositoryManager manager = mock(IMetadataRepositoryManager.class);
        when(manager.loadRepository(any(URI.class), any())).thenAnswer(invocation -> {
            allStarted.countDown();
            release.await();
            return null;
        });
        ExecutorService pool = Executors.newFixedThreadPool(n);
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<?> call = runner.submit(() -> MetadataRepositoryPrewarmer.prewarm(locations(n), manager, pool));

            assertTrue("expected all " + n + " repository loads to be in flight concurrently",
                    allStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            release.countDown();
            call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            runner.shutdownNow();
        }
    }

    /**
     * Pre-warming is best-effort: a failure to load one repository must NOT abort pre-warming or
     * propagate, because the subsequent sequential walk is what surfaces load errors (with the
     * existing message/ordering). All locations are still attempted.
     */
    @Test
    public void prewarmSwallowsFailures() throws Exception {
        URI bad = URI.create("https://example.org/bad/");
        IMetadataRepositoryManager manager = mock(IMetadataRepositoryManager.class);
        when(manager.loadRepository(any(URI.class), any())).thenAnswer(invocation -> {
            URI location = invocation.getArgument(0);
            if (location.equals(bad)) {
                throw new ProvisionException("boom");
            }
            return null;
        });
        List<MavenRepositoryLocation> locations = List.of(
                new MavenRepositoryLocation("ok1", URI.create("https://example.org/ok1/")),
                new MavenRepositoryLocation("bad", bad),
                new MavenRepositoryLocation("ok2", URI.create("https://example.org/ok2/")));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            // must not throw
            MetadataRepositoryPrewarmer.prewarm(locations, manager, pool);
            verify(manager, times(3)).loadRepository(any(URI.class), any());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * p2/Equinox load code run on a worker thread may rely on the thread-context classloader, so the
     * submitting thread's TCCL must be propagated onto the pool worker.
     */
    @Test
    public void tcclPropagatedToWorker() throws Exception {
        ClassLoader marker = new URLClassLoader(new URL[0], getClass().getClassLoader());
        AtomicReference<ClassLoader> seenOnWorker = new AtomicReference<>();
        IMetadataRepositoryManager manager = mock(IMetadataRepositoryManager.class);
        when(manager.loadRepository(any(URI.class), any())).thenAnswer(invocation -> {
            seenOnWorker.set(Thread.currentThread().getContextClassLoader());
            return null;
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(marker);
            MetadataRepositoryPrewarmer.prewarm(locations(1), manager, pool);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            pool.shutdownNow();
        }
        assertSame("worker thread must see the caller's context classloader", marker, seenOnWorker.get());
    }
}
