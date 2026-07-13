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

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.tycho.MavenRepositoryLocation;

/**
 * Loads the top-level metadata repositories of a target platform up-front so that their content and
 * artifacts indices download concurrently instead of one repository after another. Loading is
 * best-effort: the (unchanged) sequential resolution walk re-reads each repository from the p2
 * manager's cache and is responsible for surfacing load errors, so failures here are ignored.
 */
final class MetadataRepositoryPrewarmer {

    private MetadataRepositoryPrewarmer() {
    }

    static void prewarm(Collection<MavenRepositoryLocation> locations, IMetadataRepositoryManager manager,
            Executor executor) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        CompletableFuture<?>[] loads = locations.stream()
                .map(location -> CompletableFuture.runAsync(() -> load(location, manager, contextClassLoader), executor))
                .toArray(CompletableFuture[]::new);
        // Block until all top-level repositories are loaded (and cached in the p2 manager); the
        // subsequent sequential walk then reads them from the cache in its original order.
        CompletableFuture.allOf(loads).join();
    }

    private static void load(MavenRepositoryLocation location, IMetadataRepositoryManager manager,
            ClassLoader contextClassLoader) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(contextClassLoader);
        try {
            // A fresh NullProgressMonitor per worker: the shared resolution monitor is not
            // thread-safe, and download progress is reported by the transport layer regardless.
            manager.loadRepository(location.getURL(), new NullProgressMonitor());
        } catch (Exception e) {
            // best-effort pre-warming: the sequential walk re-loads (from cache) and surfaces any error
        } finally {
            current.setContextClassLoader(previous);
        }
    }
}
