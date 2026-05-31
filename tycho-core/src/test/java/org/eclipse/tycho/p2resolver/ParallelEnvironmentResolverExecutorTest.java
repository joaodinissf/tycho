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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ParallelEnvironmentResolverExecutorTest {

    private String previous;

    @Before
    public void saveProperty() {
        previous = System.getProperty(ParallelEnvironmentResolver.MAX_THREADS_PROPERTY);
    }

    @After
    public void restoreProperty() {
        if (previous == null) {
            System.clearProperty(ParallelEnvironmentResolver.MAX_THREADS_PROPERTY);
        } else {
            System.setProperty(ParallelEnvironmentResolver.MAX_THREADS_PROPERTY, previous);
        }
    }

    @Test
    public void defaultsToAvailableProcessors() {
        System.clearProperty(ParallelEnvironmentResolver.MAX_THREADS_PROPERTY);
        assertEquals(Runtime.getRuntime().availableProcessors(), ParallelEnvironmentResolver.resolverThreadCount());
    }

    @Test
    public void honorsSystemProperty() {
        System.setProperty(ParallelEnvironmentResolver.MAX_THREADS_PROPERTY, "3");
        assertEquals(3, ParallelEnvironmentResolver.resolverThreadCount());
    }

    @Test
    public void serialWhenSetToOne() {
        System.setProperty(ParallelEnvironmentResolver.MAX_THREADS_PROPERTY, "1");
        assertEquals(1, ParallelEnvironmentResolver.resolverThreadCount());
    }

    @Test
    public void clampsToAtLeastOne() {
        System.setProperty(ParallelEnvironmentResolver.MAX_THREADS_PROPERTY, "0");
        assertEquals(1, ParallelEnvironmentResolver.resolverThreadCount());
    }
}
