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
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.junit.Test;

public class FsMonitorIndexQueryOperationTest extends GitTestCase {
	@Test
	public void storedDirtyAndReportedPathsSurviveRepeatedRefreshJobs()
			throws Exception {
		checkRefresh(false);
	}

	@Test
	public void failedPersistedQueryFallsBackForRestoredMtimeChanges()
			throws Exception {
		checkRefresh(true);
	}

	private void checkRefresh(boolean failure) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry entry = null;
		try {
			fixture.connect(project.getProject());
			var repo = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repo);
			Path root = repo.getWorkTree().toPath();
			Files.createDirectories(root.resolve("sub"));
			Files.writeString(root.resolve("sub/leaf"), "base\n");
			// Reach the real two-worker threshold; an enabled option on a
			// four-entry index would not exercise parallel preloading.
			for (int i = 0; i < 996; i++) {
				Files.writeString(root.resolve("sub/extra-" + i), "base\n");
			}
			NativeFsMonitorIndexSupport.report(repo, "sub/leaf");
			var staged = NativeFsMonitorIndexSupport.mutate(repo, "add",
					"sub");
			Files.copy(staged.index(), repo.getIndexFile().toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			try (Git git = Git.wrap(repo)) {
				git.commit().setMessage("nested")
						.setAuthor("Test", "test@example.invalid")
						.setCommitter("Test", "test@example.invalid").call();
			}
			NativeFsMonitorIndexSupport.report(repo);
			assertEquals(1000, repo.readDirCache().getEntryCount());
			byte[] index = Files.readAllBytes(repo.getIndexFile().toPath());
			entry = new IndexDiffCacheEntry(repo, null);
			waitForJobs();
			for (String path : List.of("m", "sub/leaf")) {
				var time = Files.getLastModifiedTime(root.resolve(path));
				Files.writeString(root.resolve(path), "next\n");
				Files.setLastModifiedTime(root.resolve(path), time);
			}
			NativeFsMonitorIndexSupport.report(repo, "sub/leaf");
			if (failure) {
				Files.writeString(Path.of(
						repo.getConfig().getString("core", null, "fsmonitor")),
						"#!/bin/sh\nexit 1\n");
			}
			for (boolean preload : new boolean[] { false, true }) {
				repo.getConfig().setBoolean("core", null, "preloadIndex",
						preload);
				for (int i = 0; i < 2; i++) {
					entry.refresh();
					waitForJobs();
					byte[] nativeStatus = NativeFsMonitorIndexSupport
							.status(repo, !failure);
					Set<String> modified = new HashSet<>(),
							untracked = new HashSet<>();
					for (String record : new String(nativeStatus,
							StandardCharsets.UTF_8).split("\0")) {
						if (record.startsWith(" M "))
							modified.add(record.substring(3));
						else if (record.startsWith("?? "))
							untracked.add(record.substring(3));
						else if (!record.isEmpty())
							throw new AssertionError(record);
					}
					IndexDiffData actual = entry.getIndexDiff();
					assertEquals("Native fixture, preload=" + preload
							+ ", iteration=" + i + ", response="
							+ Files.readString(repo.getDirectory().toPath()
									.resolve("fsmonitor-index.response"))
							+ ", bitmap=" + repo.readDirCache()
									.getFsMonitorCache().getDirtyEntries(),
							Set.of("m", "sub/leaf"), modified);
					assertEquals(modified, actual.getModified());
					assertEquals(untracked, actual.getUntracked());
					assertTrue(actual.getAdded().isEmpty());
					assertTrue(actual.getChanged().isEmpty());
					assertTrue(actual.getRemoved().isEmpty());
					assertTrue(actual.getMissing().isEmpty());
					assertTrue(actual.getConflicting().isEmpty());
					assertTrue(actual.getIgnoredNotInIndex().isEmpty());
					assertArrayEquals(
							"Refresh cannot advance tokens before index publication",
							index,
							Files.readAllBytes(repo.getIndexFile().toPath()));
				}
			}
		} finally {
			if (entry != null)
				entry.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}
}
