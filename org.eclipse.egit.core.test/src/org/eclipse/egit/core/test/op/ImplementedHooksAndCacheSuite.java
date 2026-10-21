/*******************************************************************************
 * Copyright (c) 2026 and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.test.op;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Opt-in compatibility checks for the implemented hooks and index/status
 * caches. Run in a fresh PDE JVM with GIT_TRACE2_EVENT set to an absolute file.
 * Inclusion records test scope and does not imply complete native suite parity.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({ PostCheckoutHookOperationTest.class,
		PostMergeHookOperationTest.class, PreMergeCommitHookOperationTest.class,
		PreRebaseHookOperationTest.class, PostRewriteHookOperationTest.class,
		PreAutoGcHookOperationTest.class,
		PostIndexChangeHookOperationTest.class,
		ReceiveHookPushOperationTest.class,
		PushToCheckoutPushOperationTest.class,
		ConfiguredHookOperationTest.class, PrepareCommitMsgOperationTest.class,
		CommitOperationHookTrace2Test.class, IndexSkipHashOperationTest.class,
		CommitHookOperationTest.class, CommitEmptyTreeOperationTest.class, PrePushHookOperationTest.class,
		NativePushDefaultOperationTest.class,
		org.eclipse.egit.core.internal.indexdiff.NativeCacheIndexDiffTest.class,
		org.eclipse.egit.core.internal.indexdiff.FsMonitorIndexDiffCacheTest.class,
		org.eclipse.egit.core.internal.indexdiff.UntrackedCacheIndexDiffCacheTest.class,
		org.eclipse.egit.core.internal.indexdiff.Trace2OperationsSuite.class,
		org.eclipse.egit.core.internal.indexdiff.Trace2IndexDiffCacheSuite.class })
public class ImplementedHooksAndCacheSuite {
}
