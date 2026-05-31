/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.tycho.p2.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.equinox.internal.p2.metadata.ArtifactKey;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.metadata.Version;
import org.junit.Test;

/**
 * Tests {@link MirroringArtifactProvider#hasQualifier(IArtifactKey)}, which decides whether an
 * artifact's version carries an OSGi qualifier. A fully-qualified version is immutable by OSGi
 * contract (real content changes bump the qualifier), so a locally-cached copy may be trusted even
 * if the remote's published byte-checksum has drifted (e.g. the artifact was re-signed or
 * re-zipped); a non-qualified version is treated conservatively (no such guarantee).
 */
public class MirroringArtifactProviderQualifierTest {

    private static IArtifactKey key(String version) {
        return new ArtifactKey("osgi.bundle", "org.example.bundle", Version.create(version));
    }

    @Test
    public void qualifiedVersionsAreImmutableByContract() {
        assertTrue(MirroringArtifactProvider.hasQualifier(key("1.2.3.v20230809-1000")));
        assertTrue(MirroringArtifactProvider.hasQualifier(key("2.2.0.v20230809-1000")));
        assertTrue(MirroringArtifactProvider.hasQualifier(key("1.1.1.202109301733")));
    }

    @Test
    public void nonQualifiedVersionsAreNotTrusted() {
        assertFalse(MirroringArtifactProvider.hasQualifier(key("1.9.0")));
        assertFalse(MirroringArtifactProvider.hasQualifier(key("2.10.1")));
        assertFalse(MirroringArtifactProvider.hasQualifier(key("1.0.0")));
    }
}
