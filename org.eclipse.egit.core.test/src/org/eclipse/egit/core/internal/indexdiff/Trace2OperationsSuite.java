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

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.util.Trace2;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Opt-in operation and cache regression suite for a fresh PDE process with GIT_TRACE2_EVENT
 * pointing to an absolute file. Run explicitly with -Dtest=Trace2OperationsSuite.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
		IndexDiffCacheTest.class,
		IndexDiffCacheEntryTest.class,
		org.eclipse.egit.core.test.op.AddOperationTest.class,
		org.eclipse.egit.core.test.op.BranchOperationTest.class,
		org.eclipse.egit.core.test.op.CloneOperationTest.class,
		org.eclipse.egit.core.test.op.CommitOperationTest.class,
		org.eclipse.egit.core.test.op.ConnectProviderOperationTest.class,
		org.eclipse.egit.core.test.op.CreatePatchOperationTest.class,
		org.eclipse.egit.core.test.op.DeletePathsOperationTest.class,
		org.eclipse.egit.core.test.op.DiscardChangesOperationTest.class,
		org.eclipse.egit.core.test.op.EditCommitOperationTest.class,
		org.eclipse.egit.core.test.op.IgnoreOperationTest.class,
		org.eclipse.egit.core.test.op.ListRemoteOperationTest.class,
		org.eclipse.egit.core.test.op.MergeOperationTest.class,
		org.eclipse.egit.core.test.op.PushOperationTest.class,
		org.eclipse.egit.core.test.op.RebaseOperationTest.class,
		org.eclipse.egit.core.test.op.RemoveFromIndexOperationTest.class,
		org.eclipse.egit.core.test.op.ResetOperationTest.class,
		org.eclipse.egit.core.test.op.RewordCommitsOperationTest.class,
		org.eclipse.egit.core.test.op.SquashCommitsOperationTest.class,
		org.eclipse.egit.core.test.op.StashCreateOperationTest.class,
		org.eclipse.egit.core.test.op.TagOperationTest.class,
		org.eclipse.egit.core.test.op.TrackUntrackOperationTest.class })
public class Trace2OperationsSuite {
	private static Path events;

	@BeforeClass
	public static void startTracing() {
		String target = System.getenv("GIT_TRACE2_EVENT"); //$NON-NLS-1$
		assertTrue("Set GIT_TRACE2_EVENT to an absolute output file", //$NON-NLS-1$
				target != null && Path.of(target).isAbsolute());
		events = Path.of(target);
		// Initialize before GitTestCase installs MockSystemReader, which hides
		// the real environment. This suite must run in its own PDE process.
		try (Trace2.Scope scope = Trace2.getInstance().region(null, "test", //$NON-NLS-1$
				"initialize")) { //$NON-NLS-1$
			assertTrue("Trace2 must be enabled for this suite", scope.isEnabled()); //$NON-NLS-1$
		}
	}

	@AfterClass
	public static void verifyTracing() throws Exception {
		Trace2.getInstance().close();
		String output = Files.readString(events);
		assertTrue(output.contains("\"event\":\"cmd_name\"")); //$NON-NLS-1$
		assertTrue(output.contains("\"name\":\"status\"")); //$NON-NLS-1$
		assertTrue(output.contains("\"value\":\"egit.indexdiff.full\"")); //$NON-NLS-1$
		assertTrue(output.contains("\"value\":\"egit.indexdiff.incremental\"")); //$NON-NLS-1$
		Trace2IndexDiffCacheSuite.verifyWorkerRegions(output);
	}
}
