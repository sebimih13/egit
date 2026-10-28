/*******************************************************************************
 * Copyright (C) 2026, and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import java.nio.file.Files;
import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeFsMonitorCheckoutSupport;
import org.eclipse.jgit.lib.Constants;
import org.junit.Test;

/** Native t7519 protections through actual EGit branch checkout. */
public class FsMonitorCheckoutOperationTest extends GitTestCase {
	@Test
	public void reportedSameStatChangeCannotBeOverwritten() throws Exception {
		checkConflict("reported", false);
	}

	@Test
	public void earlierDirtyEntrySurvivesWriterClockAdvance() throws Exception {
		checkConflict("dirty", false);
	}

	@Test
	public void failedProviderCannotHideSameStatChange() throws Exception {
		checkConflict("failure", false);
	}

	@Test
	public void rootReplyCannotHideSameStatChange() throws Exception {
		checkConflict("root", false);
	}

	@Test
	public void statusResultCacheCannotBypassCheckoutValidation()
			throws Exception {
		checkConflict("reported", true);
	}

	@Test
	public void assumedEntryCannotHideCheckoutConflictWithReportedMonitor()
			throws Exception {
		checkCheckout("reported", false, "--assume-unchanged", false);
	}

	@Test
	public void assumedEntryCannotHideCheckoutConflictWithDisabledMonitor()
			throws Exception {
		checkCheckout("disabled", false, "--assume-unchanged", false);
	}

	@Test
	public void assumedEntryCannotHideCheckoutConflictWithFailureMonitor()
			throws Exception {
		checkCheckout("failure", false, "--assume-unchanged", false);
	}

	@Test
	public void cleanAssumedEntryCanBeCheckedOut() throws Exception {
		checkCheckout("reported", false, "--assume-unchanged", true);
	}

	@Test
	public void skippedEntryCannotHideCheckoutConflictWithReportedMonitor()
			throws Exception {
		checkCheckout("reported", false, "--skip-worktree", false);
	}

	@Test
	public void skippedEntryCannotHideCheckoutConflictWithDisabledMonitor()
			throws Exception {
		checkCheckout("disabled", false, "--skip-worktree", false);
	}

	@Test
	public void skippedEntryCannotHideCheckoutConflictWithFailureMonitor()
			throws Exception {
		checkCheckout("failure", false, "--skip-worktree", false);
	}

	@Test
	public void cleanSkippedEntryCanBeCheckedOut() throws Exception {
		checkCheckout("reported", false, "--skip-worktree", true);
	}

	private void checkConflict(String mode, boolean resultCache)
			throws Exception {
		checkCheckout(mode, resultCache, null, false);
	}

	private void checkCheckout(String mode, boolean resultCache, String flag,
			boolean clean) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			var scenario = NativeFsMonitorCheckoutSupport.prepare(repository,
					mode, resultCache, flag, clean);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			if (flag != null) {
				try (Git git = Git.wrap(repository)) {
					assertEquals(java.util.Set.of(), git.status()
						.setReportIgnored(false).call().getModified());
				}
			}
			BranchOperation operation = new BranchOperation(repository,
					scenario.target().name());
			operation.execute(null);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertEquals(clean ? CheckoutResult.Status.OK
					: CheckoutResult.Status.CONFLICTS,
					operation.getResult(repository).getStatus());
			assertEquals(clean ? "dest\n" : "edit\n",
					Files.readString(scenario.file()));
			assertEquals(clean ? scenario.target() : scenario.base(),
					repository.resolve(Constants.HEAD));
			if (!clean) {
				assertArrayEquals(scenario.originalIndex(),
						Files.readAllBytes(repository.getIndexFile().toPath()));
			}
		} finally {
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			fixture.dispose();
		}
	}
}
