/*******************************************************************************
 * Copyright (C) 2026, and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.util.Trace2;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Opt-in suite for a fresh PDE JVM connected to a running Trace2 receiver.
 * The external receiver harness verifies downstream spans and metadata.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({ IndexDiffCacheTest.class, IndexDiffCacheEntryTest.class,
		FsMonitorIndexDiffCacheTest.class,
		org.eclipse.egit.core.test.op.AddOperationTest.class,
		org.eclipse.egit.core.test.op.CommitOperationTest.class,
		org.eclipse.egit.core.test.op.BranchOperationTest.class,
		org.eclipse.egit.core.test.op.ResetOperationTest.class })
public class Trace2ReceiverSuite {
	@BeforeClass
	public static void startTracing() {
		String target = System.getenv("GIT_TRACE2_EVENT"); //$NON-NLS-1$
		assertTrue("Set GIT_TRACE2_EVENT to a running Unix stream receiver", //$NON-NLS-1$
				target != null && target.startsWith("af_unix:")); //$NON-NLS-1$
		// Initialize before GitTestCase replaces the real SystemReader.
		assertTrue("Trace2 destination must be available", //$NON-NLS-1$
				Trace2.getInstance().isEnabled());
	}

	@AfterClass
	public static void finishTracing() {
		// This opt-in suite owns its test JVM's library session. Closing it
		// delivers EOF so the receiver can export before the harness exits.
		Trace2.getInstance().close();
	}
}
