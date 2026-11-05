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

/** Hook, hook tracing and commit-message regressions for the 7.3 backport. */
@RunWith(Suite.class)
@Suite.SuiteClasses({ CommitOperationHookTrace2Test.class,
		ConfiguredHookOperationTest.class, CommitHookOperationTest.class,
		PrepareCommitMsgOperationTest.class, PreMergeCommitHookOperationTest.class,
		PostCheckoutHookOperationTest.class, PostMergeHookOperationTest.class,
		PreRebaseHookOperationTest.class, PostRewriteHookOperationTest.class,
		PostIndexChangeHookOperationTest.class, PreAutoGcHookOperationTest.class,
		PrePushHookOperationTest.class, ReceiveHookPushOperationTest.class,
		PushToCheckoutPushOperationTest.class,
		NativeCommitMessageOperationTest.class })
public class Trace2HooksSuite {
	// Each test owns and restores its tracing configuration.
}
