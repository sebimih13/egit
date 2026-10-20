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
		CommitOperation operation = new CommitOperation(db, TestUtils.AUTHOR,
				TestUtils.COMMITTER, "first");
		operation.execute(new NullProgressMonitor());
		return operation.getCommit();
	}
}
