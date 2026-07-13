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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.tycho.MavenRepositoryLocation;
import org.eclipse.tycho.test.util.HttpServer;
import org.eclipse.tycho.testing.TychoPlexusTestCase;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration test for {@link MetadataRepositoryPrewarmer} exercising the <em>real</em> p2
 * {@link IMetadataRepositoryManager} (not a mock). This is the verification that concurrent
 * {@code loadRepository} calls against Equinox's shared metadata manager are safe — the one residual
 * risk of loading repositories in parallel. Several repositories (the same small p2 repository served
 * under distinct HTTP paths) are pre-warmed at once; each must load successfully and actually be
 * fetched from the server.
 */
public class MetadataRepositoryPrewarmerIntegrationTest extends TychoPlexusTestCase {

    @Rule
    public final HttpServer localServer = new HttpServer();

    @Test
    public void prewarmsMultipleRepositoriesConcurrentlyAgainstRealManager() throws Exception {
        int repositoryCount = 6;
        File repoContent = new File("src/test/resources/repositories/e342");
        List<MavenRepositoryLocation> locations = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < repositoryCount; i++) {
            String path = "repo" + i;
            paths.add(path);
            URI uri = URI.create(localServer.addServlet(path, repoContent));
            locations.add(new MavenRepositoryLocation("r" + i, uri));
        }

        IMetadataRepositoryManager manager = lookup(IProvisioningAgent.class)
                .getService(IMetadataRepositoryManager.class);
        ExecutorService pool = Executors.newFixedThreadPool(repositoryCount);
        try {
            // concurrent loads against the real Equinox manager must not throw or corrupt state
            MetadataRepositoryPrewarmer.prewarm(locations, manager, pool);
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < repositoryCount; i++) {
            // each repository loaded successfully (now served from the manager cache) ...
            IMetadataRepository repository = manager.loadRepository(locations.get(i).getURL(), null);
            assertNotNull("repository " + i + " should have been loaded by pre-warming", repository);
            // ... and was actually fetched from the server during pre-warming
            assertFalse("repository " + i + " should have been fetched from the server",
                    localServer.getAccessedUrls(paths.get(i)).isEmpty());
        }
    }
}
