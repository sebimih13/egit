/*******************************************************************************
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.op.UntrackOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.junit.NativeSparseUntrackedSupport;
import org.eclipse.jgit.junit.NativeSparseUntrackedSupport.Snapshot;
import org.eclipse.jgit.junit.NativeUntrackedNormalSupport;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Original sparse-ignore transitions through retained EGit jobs and operations.
 */
@RunWith(Parameterized.class)
public class SparseUntrackedCacheOperationTest extends GitTestCase {
	@Parameterized.Parameters(name = "cache={0}, preload={1}, sparseConfig={2}")
	public static List<Object[]> scenarios() {
		List<Object[]> result = new ArrayList<>();
		for (boolean cache : new boolean[] { false, true })
			for (boolean preload : new boolean[] { false, true })
				for (boolean sparse : new boolean[] { false, true })
					result.add(new Object[] { cache, preload, sparse });
		return result;
	}

	private final boolean cache;

	private final boolean preload;

	private final boolean sparse;

	private TestRepository fixture;

	private Repository repository;

	private IndexDiffCacheEntry entry;

	public SparseUntrackedCacheOperationTest(boolean cache, boolean preload,
			boolean sparse) {
		this.cache = cache;
		this.preload = preload;
		this.sparse = sparse;
	}

	private void prepare(boolean monitor) throws Exception {
		// This SCM fixture needs resources, not asynchronous Java build output.
		var description = project.getProject().getDescription();
		description.setNatureIds(new String[0]);
		description.setBuildSpec(new org.eclipse.core.resources.ICommand[0]);
		project.getProject().setDescription(description, null);
		TestUtils.waitForJobs(50, 10000, null);
		fixture = new TestRepository(
				new File(project.getProject().getLocation().toFile(), ".git"));
		repository = fixture.getRepository();
		NativeSparseUntrackedSupport.prepare(repository, cache, preload,
				sparse);
		if (monitor) {
			repository.getConfig().setBoolean("feature", null, "manyFiles",
					true);
			repository.getConfig().save();
			NativeUntrackedNormalSupport.enableMonitor(repository);
		}
		// Mandatory setup writers finish before background maintenance starts.
		fixture.connect(project.getProject());
		waitForRefresh();
		entry = new IndexDiffCacheEntry(repository, null);
		waitForRefresh();
	}

	private static void waitForRefresh() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}

	@After
	public void disposeSparseFixture() throws Exception {
		if (entry != null)
			entry.dispose();
		waitForRefresh();
		if (fixture != null)
			fixture.dispose();
	}

	@Test
	public void sparseRulesNestedPathsAndMembershipMatchNative()
			throws Exception {
		prepare(false);
		check(NativeSparseUntrackedSupport.expected(false, false));
		check(NativeSparseUntrackedSupport.expected(false, false));
		NativeUntrackedNormalSupport.write(repository, "done/sub/sub/file",
				"sub\n");
		NativeUntrackedNormalSupport.ageDirectories(repository);
		check(NativeSparseUntrackedSupport.expected(true, false));
		check(NativeSparseUntrackedSupport.expected(true, false));
		project.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
		waitForRefresh();
		new AddToIndexOperation(
				new IResource[] { project.getProject().getFile("dtwo/two") })
				.execute(null);
		check(NativeSparseUntrackedSupport.expected(true, true));
		new UntrackOperation(repository,
				List.of(new org.eclipse.core.runtime.Path(
						new File(repository.getWorkTree(), "dtwo/two")
								.getAbsolutePath())))
				.execute(null);
		check(NativeSparseUntrackedSupport.expected(true, false));
	}

	@Test
	public void presentWorktreeIgnoreOverridesIndexedRules() throws Exception {
		prepare(false);

		check(NativeSparseUntrackedSupport.expected(false, false));
		NativeUntrackedNormalSupport.write(repository, "done/.gitignore",
				"five\n");
		NativeUntrackedNormalSupport.ageDirectories(repository);
		check(NativeSparseUntrackedSupport.worktreeRules(sparse));
		check(NativeSparseUntrackedSupport.worktreeRules(sparse));

	}

	@Test
	public void indexedRulesComposeWithParentAndGlobalRules() throws Exception {
		prepare(false);
		NativeUntrackedNormalSupport.write(repository, ".gitignore",
				"four\nparent-only\n");
		NativeUntrackedNormalSupport.write(repository, "done/parent-only",
				"parent\n");
		NativeUntrackedNormalSupport.write(repository, "done/global-only",
				"global\n");
		java.nio.file.Path excludes = repository.getDirectory().toPath()
				.resolve("sparse-global-excludes");
		java.nio.file.Files.writeString(excludes, "global-only\n!done/three\n");
		repository.getConfig().setString("core", null, "excludesFile",
				excludes.toString());
		repository.getConfig().save();
		NativeUntrackedNormalSupport.ageDirectories(repository);

		check(NativeSparseUntrackedSupport.parentAndGlobalRules());
		check(NativeSparseUntrackedSupport.parentAndGlobalRules());

	}

	@Test
	public void expectedFilesOutsidePatternsRetainExplicitSkipHints()
			throws Exception {
		prepare(false);
		repository.getConfig().setBoolean("sparse", null,
				"expectFilesOutsideOfPatterns", true);
		repository.getConfig().save();
		NativeUntrackedNormalSupport.write(repository, "done/.gitignore",
				"five\n");
		NativeUntrackedNormalSupport.ageDirectories(repository);

		check(NativeSparseUntrackedSupport.worktreeRules(false));
		check(NativeSparseUntrackedSupport.worktreeRules(false));
		assertTrue(repository.readDirCache().getEntry("done/.gitignore")
				.isSkipWorkTree());

	}

	@Test
	public void ordinaryDeletedIgnoreDoesNotReadIndexedRules()
			throws Exception {
		prepare(false);
		NativeSparseUntrackedSupport.clearIgnoreSkipFlag(repository);
		check(NativeSparseUntrackedSupport.ordinaryDeletion());
	}

	@Test
	public void changedSkippedBlobInvalidatesPriorIgnoreRules()
			throws Exception {
		prepare(false);
		check(NativeSparseUntrackedSupport.expected(false, false));
		NativeSparseUntrackedSupport.replaceIndexedRules(repository);
		check(NativeSparseUntrackedSupport.changedIndexRules());
		check(NativeSparseUntrackedSupport.changedIndexRules());
	}

	@Test
	public void monitorAndManyFilesKeepSparseIgnoreRulesAcrossQuietCalls()
			throws Exception {
		prepare(true);
		check(NativeSparseUntrackedSupport.expected(false, false));
		check(NativeSparseUntrackedSupport.expected(false, false));
	}

	private void check(Snapshot expected) throws Exception {
		waitForRefresh();
		NativeSparseUntrackedSupport.assertNative(repository, expected);
		entry.refresh();
		waitForRefresh();
		IndexDiffData data = entry.getIndexDiff();
		assertEquals(expected.modified(), data.getModified());
		assertEquals(expected.untracked(), data.getUntracked());
		assertEquals(expected.ignored(), data.getIgnoredNotInIndex());
		assertEquals(expected.added(), data.getAdded());
		assertEquals(expected.changed(), data.getChanged());
		assertEquals(expected.missing(), data.getMissing());
		assertTrue(data.getRemoved().isEmpty());
		assertTrue(data.getConflicting().isEmpty());
		NativeSparseUntrackedSupport.assertPublication(repository, expected,
				cache);
	}
}
