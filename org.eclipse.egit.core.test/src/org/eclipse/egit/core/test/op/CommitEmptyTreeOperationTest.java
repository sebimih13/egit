/*******************************************************************************
 * Copyright (c) 2026 and others.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.test.op;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.op.CommitOperation;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.junit.CommitEmptyTreeTestCase;
import org.eclipse.jgit.revwalk.RevCommit;

/** Native empty-tree cases through the actual EGit CommitOperation. */
public class CommitEmptyTreeOperationTest extends CommitEmptyTreeTestCase {
	@Override
	protected RevCommit commit(boolean allowEmpty, boolean amend, String message) throws Exception {
		CommitOperation operation = new CommitOperation(db, TestUtils.AUTHOR, TestUtils.COMMITTER, message);
		operation.setAllowEmpty(allowEmpty);
		operation.setAmending(amend);
		operation.execute(new NullProgressMonitor());
		return operation.getCommit();
	}
}
