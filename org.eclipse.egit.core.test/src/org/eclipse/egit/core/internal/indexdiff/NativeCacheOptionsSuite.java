/*******************************************************************************
 * Copyright (c) 2026 and others.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import org.eclipse.egit.core.test.op.IndexSkipHashOperationTest;
import org.eclipse.egit.core.test.op.NativeIndexVersionOperationTest;
import org.eclipse.jgit.util.Trace2;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Opt-in validation of the implemented FSMonitor, untracked-cache, preload and
 * many-files paths through EGit jobs and operations. Passing does not establish
 * support for every native index extension or daemon protocol.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({ NativeCacheIndexDiffTest.class,
		FsMonitorIndexDiffCacheTest.class, FsMonitorIndexStorageTest.class,
		FsMonitorIndexQueryOperationTest.class,
		FsMonitorInitializationOperationTest.class,
		UntrackedCacheIndexDiffCacheTest.class,
		PreloadIndexDiffCacheTest.class, IndexSkipHashOperationTest.class,
		NativeIndexVersionOperationTest.class })
public class NativeCacheOptionsSuite {
	/** Initialize before GitTestCase replaces the environment reader. */
	@BeforeClass
	public static void initializeOptionalTracing() {
		Trace2.getInstance();
	}
}
