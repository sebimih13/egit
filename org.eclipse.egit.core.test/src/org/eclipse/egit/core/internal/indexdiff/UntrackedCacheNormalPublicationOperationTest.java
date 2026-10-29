/*******************************************************************************
 * Copyright (C) 2026, and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.junit.NativeUntrackedNormalSupport;
import org.eclipse.jgit.util.FileUtils;
import org.junit.Test;

/** Native t7063 normal cache publication through retained EGit refresh jobs. */
public class UntrackedCacheNormalPublicationOperationTest extends GitTestCase {
	@Test
	public void defaultModePublishesNativeSummaries() throws Exception {
		checkPublication(null, false);
	}

	@Test
	public void explicitNormalModePublishesNativeSummaries() throws Exception {
		checkPublication("normal", false);
	}

	@Test
	public void nestedSummariesRefreshAfterWitnessRemovalAndIgnoreEdits()
			throws Exception {
		checkPublication("normal", true);
	}

	@Test
	public void monitoredDefaultModeKeepsQuietResultReuse() throws Exception {
		checkPublication(null, false, true);
	}

	private void checkPublication(String mode, boolean mutate)
			throws Exception {
		checkPublication(mode, mutate, false);
	}

	private void checkPublication(String mode, boolean mutate, boolean monitor)
			throws Exception {
		Path directory = Files.createTempDirectory("egit-normal-publication-");
		TestRepository fixture = new TestRepository(
				directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		try {
			var repository = fixture.getRepository();
			NativeUntrackedNormalSupport.prepare(repository, mode);
			if (monitor)
				NativeUntrackedNormalSupport.enableMonitor(repository);
			if (mutate) {
				NativeUntrackedNormalSupport.write(repository,
						"dtwo/nested/next", "new\n");
				NativeUntrackedNormalSupport.write(repository,
						"dtwo/.gitignore", "*.tmp\n");
				NativeUntrackedNormalSupport.write(repository,
						"dtwo/hidden.tmp", "ignored\n");
				Files.createDirectories(directory.resolve("empty"));
				NativeUntrackedNormalSupport.ageDirectories(repository);
			}
			entry = new IndexDiffCacheEntry(repository, null, false, true);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			Set<String> expected = mutate
					? Set.of("three", "dthree/three", "dtwo/two",
							"dtwo/nested/next", "dtwo/.gitignore")
					: Set.of("three", "dthree/three", "dtwo/two");
			assertEquals(expected, entry.getIndexDiff().getUntracked());
			NativeUntrackedNormalSupport.assertReusable(repository,
					Set.of("three", "dtwo/", "dthree/"));
			if (mutate) {
				Files.delete(directory.resolve("dtwo/two"));
				Files.delete(directory.resolve("dtwo/.gitignore"));
				NativeUntrackedNormalSupport.ageDirectories(repository);
				entry.refresh();
				TestUtils.waitForJobs(10000,
						JobFamilies.INDEX_DIFF_CACHE_UPDATE);
				assertEquals(
						Set.of("three", "dthree/three", "dtwo/nested/next",
								"dtwo/hidden.tmp"),
						entry.getIndexDiff().getUntracked());
				NativeUntrackedNormalSupport.assertReusable(repository,
						Set.of("three", "dtwo/", "dthree/"));
			}
			if (monitor) {
				var first = entry.getIndexDiff();
				byte[] published = Files
						.readAllBytes(repository.getIndexFile().toPath());
				entry.refresh();
				TestUtils.waitForJobs(10000,
						JobFamilies.INDEX_DIFF_CACHE_UPDATE);
				org.junit.Assert.assertSame(first, entry.getIndexDiff());
				org.junit.Assert.assertArrayEquals(published,
						Files.readAllBytes(repository.getIndexFile().toPath()));
			}
			assertEquals(entry.getIndexDiff().getUntracked(),
					NativeUntrackedNormalSupport.nativeStatus(repository, "all",
							false));
			assertTrue(entry.getIndexDiff().getIgnoredNotInIndex().isEmpty());
			assertTrue(entry.getIndexDiff().getAdded().isEmpty());
			assertTrue(entry.getIndexDiff().getChanged().isEmpty());
			assertTrue(entry.getIndexDiff().getRemoved().isEmpty());
			assertTrue(entry.getIndexDiff().getModified().isEmpty());
			assertTrue(entry.getIndexDiff().getMissing().isEmpty());
			assertTrue(entry.getIndexDiff().getConflicting().isEmpty());
		} finally {
			if (entry != null)
				entry.dispose();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			fixture.dispose();
			FileUtils.delete(directory.toFile(),
					FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	@Test
	public void unicodeDirectorySummariesKeepCompleteDefaultResults() throws Exception {
		Path directory = Files.createTempDirectory("egit-unicode-cache-");
		TestRepository fixture = new TestRepository(directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		try {
			var repository = fixture.getRepository();
			NativeUntrackedNormalSupport.prepare(repository, null);
			NativeUntrackedNormalSupport.write(repository, "é/嵌套/loose\nfile", "new\n");
			NativeUntrackedNormalSupport.ageDirectories(repository);
			entry = new IndexDiffCacheEntry(repository, null);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			var expected = Set.of("three", "dtwo/two", "dthree/three", "é/嵌套/loose\nfile");
			assertEquals(expected, entry.getIndexDiff().getUntracked());
			NativeUntrackedNormalSupport.assertReusable(repository, Set.of("three", "dtwo/", "dthree/", "é/"));
			assertEquals(expected, NativeUntrackedNormalSupport.nativeStatus(repository, "all", false));
		} finally {
			if (entry != null) entry.dispose();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			fixture.dispose();
			FileUtils.delete(directory.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

}
