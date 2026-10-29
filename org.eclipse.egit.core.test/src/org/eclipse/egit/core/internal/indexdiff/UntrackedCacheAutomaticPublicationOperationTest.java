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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.junit.MockSystemReader;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeUntrackedNormalSupport;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;

/** Native optional maintenance through ordinary EGit refresh jobs. */
public class UntrackedCacheAutomaticPublicationOperationTest
		extends GitTestCase {
	@Test
	public void defaultRefreshMaintainsCacheAndIgnoredResults()
			throws Exception {
		check("write");
	}

	@Test
	public void manyFilesAlonePopulatesCache() throws Exception {
		check("many");
	}

	@Test
	public void keepModeMaintainsExistingCache() throws Exception {
		check("keep");
	}

	@Test
	public void disabledCacheIsRemoved() throws Exception {
		check("disabled");
	}

	@Test
	public void explicitReadOnlyCacheLeavesIndexIntact() throws Exception {
		check("read-only");
	}

	@Test
	public void optionalLockPermissionChangeForcesMaintenance()
			throws Exception {
		check("optional");
	}

	@Test
	public void unconfiguredCacheIsNotCreated() throws Exception {
		check("absent");
	}

	@Test
	public void twoDefaultCachesConvergeWithoutRepublishingEachOthersWrites()
			throws Exception {
		var directory = Files.createTempDirectory("egit-two-cache-publishers-");
		TestRepository fixture = new TestRepository(
				directory.resolve(".git").toFile());
		IndexDiffCacheEntry first = null;
		IndexDiffCacheEntry second = null;
		org.eclipse.jgit.events.ListenerHandle listener = null;
		try {
			var repository = fixture.getRepository();
			NativeUntrackedNormalSupport.prepare(repository, null);
			NativeUntrackedNormalSupport.write(repository, "done/.gitignore",
					"*.tmp\n");
			NativeUntrackedNormalSupport.write(repository, "done/hidden.tmp",
					"ignored\n");
			NativeUntrackedNormalSupport.ageDirectories(repository);
			NativeUntrackedNormalSupport.enableMonitor(repository);
			var hook = java.nio.file.Path.of(repository.getConfig()
					.getString("core", null, "fsmonitor"));
			Files.writeString(hook, "#!/bin/sh\nprintf 'clock-%s\\000' \"$$\"\n"
					+ "if test -e .git/auto-report; then printf 'done/new\\000'; fi\n");
			var writes = new java.util.concurrent.atomic.AtomicInteger();
			listener = repository.getListenerList()
					.addIndexChangedListener(event -> {
						if (event.getOperationToken() != null)
							writes.incrementAndGet();
					});
			first = new IndexDiffCacheEntry(repository, null);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			second = new IndexDiffCacheEntry(repository, null);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertStatus(first.getIndexDiff());
			assertStatus(second.getIndexDiff());
			int initial = writes.get();
			org.junit.Assert.assertTrue(initial > 0);
			byte[] published = Files
					.readAllBytes(repository.getIndexFile().toPath());
			first.refresh();
			second.refresh();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertEquals(initial, writes.get());
			assertArrayEquals(published,
					Files.readAllBytes(repository.getIndexFile().toPath()));

			NativeUntrackedNormalSupport.write(repository, "done/new",
					"next\n");
			Files.writeString(
					repository.getDirectory().toPath().resolve("auto-report"),
					"report\n");
			first.refresh();
			second.refresh();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			var expected = Set.of("three", "dtwo/two", "dthree/three",
					"done/.gitignore", "done/new");
			assertEquals(expected, first.getIndexDiff().getUntracked());
			assertEquals(expected, second.getIndexDiff().getUntracked());
			assertEquals(Set.of("done/hidden.tmp"),
					first.getIndexDiff().getIgnoredNotInIndex());
			assertEquals(Set.of("done/hidden.tmp"),
					second.getIndexDiff().getIgnoredNotInIndex());
			org.junit.Assert.assertTrue(
					"An independent refresh can still publish",
					writes.get() > initial);
			assertEquals(expected, NativeUntrackedNormalSupport
					.nativeStatus(repository, "all", false));
			Files.delete(
					repository.getDirectory().toPath().resolve("auto-report"));
			int changed = writes.get();
			published = Files.readAllBytes(repository.getIndexFile().toPath());
			first.refresh();
			second.refresh();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertEquals(changed, writes.get());
			assertArrayEquals(published,
					Files.readAllBytes(repository.getIndexFile().toPath()));
		} finally {
			if (first != null)
				first.dispose();
			if (second != null)
				second.dispose();
			if (listener != null)
				listener.remove();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			fixture.dispose();
			FileUtils.delete(directory.toFile(),
					FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	private void check(String policy) throws Exception {
		var directory = Files.createTempDirectory("egit-auto-cache-");
		TestRepository fixture = new TestRepository(
				directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		var reader = (MockSystemReader) SystemReader.getInstance();
		try {
			var repository = fixture.getRepository();
			NativeUntrackedNormalSupport.prepare(repository, null);
			NativeUntrackedNormalSupport.write(repository, "done/.gitignore",
					"*.tmp\n");
			NativeUntrackedNormalSupport.write(repository, "done/hidden.tmp",
					"ignored\n");
			NativeUntrackedNormalSupport.ageDirectories(repository);
			if (policy.equals("many") || policy.equals("absent")) {
				var removed = NativeFsMonitorIndexSupport.mutate(repository,
						"-c", "core.untrackedCache=false", "update-index",
						"--no-untracked-cache", "--force-write-index");
				Files.copy(removed.index(), repository.getIndexFile().toPath(),
						StandardCopyOption.REPLACE_EXISTING);
				repository.getConfig().unset("core", null, "untrackedCache");
				if (policy.equals("many"))
					repository.getConfig().setBoolean("feature", null,
							"manyFiles", true);
			} else if (policy.equals("keep"))
				repository.getConfig().unset("core", null, "untrackedCache");
			else if (policy.equals("disabled"))
				repository.getConfig().setBoolean("core", null,
						"untrackedCache", false);
			repository.getConfig().save();
			if (policy.equals("many"))
				NativeUntrackedNormalSupport.enableMonitor(repository);
			if (policy.equals("optional")) {
				NativeUntrackedNormalSupport.enableMonitor(repository);
				reader.setProperty("GIT_OPTIONAL_LOCKS", "0");
			}
			byte[] original = Files
					.readAllBytes(repository.getIndexFile().toPath());
			entry = policy.equals("read-only")
					? new IndexDiffCacheEntry(repository, null, true, false)
					: new IndexDiffCacheEntry(repository, null);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertStatus(entry.getIndexDiff());
			if (policy.equals("read-only") || policy.equals("optional")) {
				assertArrayEquals(original,
						Files.readAllBytes(repository.getIndexFile().toPath()));
				if (policy.equals("read-only")) {
					entry.dispose();
					TestUtils.waitForJobs(10000,
							JobFamilies.INDEX_DIFF_CACHE_UPDATE);
					entry = new IndexDiffCacheEntry(repository, null);
				} else {
					reader.setProperty("GIT_OPTIONAL_LOCKS", null);
					entry.refresh();
				}
				TestUtils.waitForJobs(10000,
						JobFamilies.INDEX_DIFF_CACHE_UPDATE);
				assertStatus(entry.getIndexDiff());
			}
			if (policy.equals("disabled") || policy.equals("absent")) {
				assertNull(repository.readDirCache().getUntrackedCache());
				if (policy.equals("absent"))
					assertArrayEquals(original, Files
							.readAllBytes(repository.getIndexFile().toPath()));
			} else {
				NativeUntrackedNormalSupport.assertReusable(repository,
						Set.of("three", "dtwo/", "dthree/", "done/.gitignore"));
			}
			if (policy.equals("optional") || policy.equals("many")) {
				var previous = entry.getIndexDiff();
				byte[] published = Files
						.readAllBytes(repository.getIndexFile().toPath());
				entry.refresh();
				TestUtils.waitForJobs(10000,
						JobFamilies.INDEX_DIFF_CACHE_UPDATE);
				assertSame(previous, entry.getIndexDiff());
				assertArrayEquals(published,
						Files.readAllBytes(repository.getIndexFile().toPath()));
			}
		} finally {
			reader.setProperty("GIT_OPTIONAL_LOCKS", null);
			if (entry != null)
				entry.dispose();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			fixture.dispose();
			FileUtils.delete(directory.toFile(),
					FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	private void assertStatus(IndexDiffData status) {
		assertEquals(
				Set.of("three", "dtwo/two", "dthree/three", "done/.gitignore"),
				status.getUntracked());
		assertEquals(Set.of("done/hidden.tmp"), status.getIgnoredNotInIndex());
		assertEquals(Set.of(), status.getAdded());
		assertEquals(Set.of(), status.getChanged());
		assertEquals(Set.of(), status.getRemoved());
		assertEquals(Set.of(), status.getModified());
		assertEquals(Set.of(), status.getMissing());
		assertEquals(Set.of(), status.getConflicting());
	}
}
