/*******************************************************************************
 * Copyright (c) 2026 and others.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.test.op;

import java.util.List;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.op.CommitOperation;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.junit.NativeCommitMessageTestCase;
import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Native message scenarios through EGit's actual commit operation. */
@RunWith(Parameterized.class)
public class NativeCommitMessageOperationTest extends NativeCommitMessageTestCase {
	public NativeCommitMessageOperationTest(String scenario) { super(scenario); }
	@Parameterized.Parameters(name = "{0}")
	public static List<Object[]> cases() { return scenarios(); }
	@Override
	protected RevCommit commitMessage(String message, CleanupMode cleanup, boolean noVerify,
			boolean allowEmptyMessage) throws Exception {
		CommitOperation operation = new CommitOperation(db, TestUtils.AUTHOR, TestUtils.COMMITTER, message);
		operation.setUseNativeMessageCleanup(true);
		operation.setCleanupMode(cleanup);
		operation.setAllowEmptyMessage(allowEmptyMessage);
		operation.setNoVerify(noVerify);
		operation.execute(new NullProgressMonitor());
		return operation.getCommit();
	}
}
