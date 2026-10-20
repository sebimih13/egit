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

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.op.CommitOperation;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.junit.CommitHookTestCase;
import org.eclipse.jgit.revwalk.RevCommit;

/** Shared scenarios through EGit's actual staged-index commit operation. */
public class CommitHookOperationTest extends CommitHookTestCase {
	@Override
	protected RevCommit commitStaged() throws Exception {
		return executeCommit(false);
	}

	@Override
	protected RevCommit commitWithoutVerification() throws Exception {
		return executeCommit(true);
	}

	private RevCommit executeCommit(boolean noVerify) throws Exception {
		CommitOperation operation = new CommitOperation(db, TestUtils.AUTHOR,
				TestUtils.COMMITTER, "first");
		operation.setNoVerify(noVerify);
		operation.execute(new NullProgressMonitor());
		return operation.getCommit();
	}

	@org.junit.Test
	public void commitAllForwardsVerificationBypass() throws Exception {
		hook("pre-commit",
				"echo unexpected >\"$GIT_DIR/commit-all-hook\"\nexit 7\n");
		writeTrashFile("file", "from worktree\n");
		CommitOperation operation = new CommitOperation(db, TestUtils.AUTHOR,
				TestUtils.COMMITTER, "all tracked changes");
		operation.setCommitAll(true);
		operation.setNoVerify(true);
		operation.execute(new NullProgressMonitor());
		org.junit.Assert.assertEquals(initial,
				operation.getCommit().getParent(0));
		try (org.eclipse.jgit.treewalk.TreeWalk tree = org.eclipse.jgit.treewalk.TreeWalk
				.forPath(db, "file", operation.getCommit().getTree())) {
			org.junit.Assert.assertNotNull(tree);
			org.junit.Assert.assertEquals("from worktree\n",
					new String(db.open(tree.getObjectId(0)).getBytes(),
							java.nio.charset.StandardCharsets.UTF_8));
		}
		org.junit.Assert.assertFalse(java.nio.file.Files
				.exists(db.getDirectory().toPath().resolve("commit-all-hook")));
	}

}
