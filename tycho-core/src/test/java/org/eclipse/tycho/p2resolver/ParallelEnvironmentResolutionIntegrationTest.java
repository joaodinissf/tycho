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

import static org.eclipse.tycho.PackagingType.TYPE_ECLIPSE_PLUGIN;
import static org.eclipse.tycho.test.util.ExecutionEnvironmentTestUtils.NOOP_EE_RESOLUTION_HANDLER;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.TargetEnvironment;
import org.eclipse.tycho.core.resolver.P2ResolutionResult;
import org.eclipse.tycho.core.resolver.P2ResolutionResult.Entry;
import org.eclipse.tycho.targetplatform.P2TargetPlatform;
import org.junit.Test;

/**
 * Exercises the concurrent per-environment resolution path ({@link ParallelEnvironmentResolver}, wired into
 * {@link P2ResolverImpl#resolveTargetDependencies}) against the real p2 {@code Projector}/{@code Slicer} solver.
 * Several environments are resolved at once over one shared target platform - the actual thread-safety hazard.
 * The test gates that concurrent solves (a) do not throw or corrupt state, (b) preserve the environment iteration
 * order, and (c) are deterministic: repeating the resolution yields byte-for-byte the same per-environment result.
 */
public class ParallelEnvironmentResolutionIntegrationTest extends P2ResolverTestBase {

    private static final int ITERATIONS = 8;

    private static List<TargetEnvironment> threeEnvironments() {
        List<TargetEnvironment> environments = new ArrayList<>();
        environments.add(new TargetEnvironment("linux", "gtk", "x86_64"));
        environments.add(new TargetEnvironment("macosx", "cocoa", "x86_64"));
        environments.add(new TargetEnvironment("win32", "win32", "x86_64"));
        return environments;
    }

    /** A stable, order-independent textual signature of a per-environment resolution result. */
    private static String signature(P2ResolutionResult result) {
        TreeSet<String> units = new TreeSet<>();
        for (Object unit : result.getNonReactorUnits()) {
            units.add(String.valueOf(unit));
        }
        TreeSet<String> artifacts = new TreeSet<>();
        for (Entry entry : result.getArtifacts()) {
            artifacts.add(entry.getId() + ":" + entry.getVersion());
        }
        return "units=" + units + " artifacts=" + artifacts;
    }

    @Test
    public void concurrentMultiEnvResolutionIsSafeOrderedAndDeterministic() throws Exception {
        List<TargetEnvironment> environments = threeEnvironments();
        pomDependencies = resolverFactory.newPomDependencyCollector();
        tpConfig.addP2Repository(resourceFile("repositories/e342_2").toURI());
        ReactorProject projectToResolve = createReactorProject(resourceFile("resolver/bundle01"), TYPE_ECLIPSE_PLUGIN,
                "org.eclipse.tycho.p2.impl.resolver.test.bundle01");

        List<String> reference = null;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            P2ResolverImpl resolver = new P2ResolverImpl(tpFactory, null, logVerifier.getMavenLogger(), environments);
            P2TargetPlatform targetPlatform = tpFactory.createTargetPlatformWithUpdatedReactorContent(
                    tpFactory.createTargetPlatform(tpConfig, NOOP_EE_RESOLUTION_HANDLER, reactorProjects),
                    Collections.emptyList(), pomDependencies);

            Map<TargetEnvironment, P2ResolutionResult> results = resolver.resolveTargetDependencies(targetPlatform,
                    projectToResolve);

            // (b) one result per environment, in the configured order
            assertEquals(environments, new ArrayList<>(results.keySet()));

            // (c) deterministic across iterations (would flake/diverge if concurrent solves raced)
            List<String> signatures = new ArrayList<>();
            for (TargetEnvironment environment : environments) {
                signatures.add(signature(results.get(environment)));
            }
            if (reference == null) {
                reference = signatures;
            } else {
                assertEquals("resolution result diverged on iteration " + iteration, reference, signatures);
            }
        }
    }
}
