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

import java.io.File;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.tycho.ArtifactDescriptor;
import org.eclipse.tycho.p2maven.transport.TychoRepositoryTransport;

/**
 * Fetches a set of artifacts so that they are available on the local file system. Distinct
 * artifacts are independent of each other, so they are fetched concurrently over a bounded thread
 * pool rather than one after another.
 */
@Component(role = ArtifactPrefetcher.class)
public class ArtifactPrefetcher {

    /**
     * Fetches all given artifacts using the shared, bounded download thread pool, blocking until all
     * of them are available locally. If one or more fetches fail, the first failure is rethrown once
     * all fetches have completed (so a single failure does not abort the others early).
     */
    public void prefetch(Collection<ArtifactDescriptor> artifacts) {
        prefetch(artifacts, TychoRepositoryTransport.getDownloadExecutor());
    }

    void prefetch(Collection<ArtifactDescriptor> artifacts, Executor executor) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        CompletableFuture<?>[] fetches = artifacts.stream()
                .map(artifact -> CompletableFuture.supplyAsync(() -> fetch(artifact, contextClassLoader), executor))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(fetches).join();
        } catch (CompletionException e) {
            // Unwrap so the original fetch failure (e.g. MirroringFailedException) propagates as it
            // did when fetching was a serial loop, rather than as a CompletionException.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    @SuppressWarnings("deprecation")
    private static File fetch(ArtifactDescriptor artifact, ClassLoader contextClassLoader) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(contextClassLoader);
        try {
            return artifact.getLocation(true);
        } finally {
            current.setContextClassLoader(previous);
        }
    }
}
