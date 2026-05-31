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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.eclipse.tycho.TargetEnvironment;

/**
 * Resolves the dependencies of a project for each configured {@link TargetEnvironment}. The per-environment
 * solves are independent of each other (each builds its own SAT problem), so they are run concurrently over a
 * bounded, CPU-sized thread pool rather than one after another.
 */
final class ParallelEnvironmentResolver {

    private ParallelEnvironmentResolver() {
    }

    /** Name of the system property controlling the number of concurrent per-environment resolution threads. */
    static final String MAX_THREADS_PROPERTY = "tycho.p2.resolver.max-threads";

    /**
     * Number of concurrent per-environment resolution threads. Defaults to the number of available processors
     * because each solve is CPU-bound; set to {@code 1} to resolve environments serially (the previous behaviour).
     * This pool is intentionally separate from the network-tuned download pool.
     */
    static int resolverThreadCount() {
        return Math.max(1,
                Integer.getInteger(MAX_THREADS_PROPERTY, Runtime.getRuntime().availableProcessors()).intValue());
    }

    private static final Executor RESOLUTION_EXECUTOR = Executors.newFixedThreadPool(resolverThreadCount(),
            new ThreadFactory() {

                private final AtomicInteger cnt = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName("Tycho-Resolution-Thread-" + cnt.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            });

    /** The shared, bounded, CPU-sized pool used for concurrent per-environment resolution. */
    static Executor getExecutor() {
        return RESOLUTION_EXECUTOR;
    }

    /**
     * Resolves each environment via {@code solve} and returns the results keyed by environment, preserving the
     * iteration order of {@code environments}. NOTE: serial baseline implementation - replaced with a concurrent
     * implementation in the following commit.
     */
    static <R> Map<TargetEnvironment, R> resolve(List<TargetEnvironment> environments,
            Function<TargetEnvironment, R> solve, Executor executor) {
        Map<TargetEnvironment, R> results = new LinkedHashMap<>();
        for (TargetEnvironment environment : environments) {
            results.put(environment, solve.apply(environment));
        }
        return results;
    }
}
