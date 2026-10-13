/*******************************************************************************
 * Copyright (C) 2026, and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.util.FileUtils;
import org.junit.Test;

public class TrackedDirectoryIndexDiffCacheTest extends GitTestCase {
	@Test
	public void fullRefreshTraversesIgnoredTrackedDirectories()
			throws Exception {
		checkRefresh(false);
	}

	@Test
	public void incrementalRefreshTraversesIgnoredTrackedDirectories()
			throws Exception {
		checkRefresh(true);
	}

	private void checkRefresh(boolean incremental) throws Exception {
		Path directory = Files.createTempDirectory("egit-tracked-directory-");
		TestRepository fixture = new TestRepository(
				directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		try (Git git = new Git(fixture.getRepository())) {
			Files.createDirectories(directory.resolve("tracked/deep"));
			Files.writeString(directory.resolve("tracked/deep/file"), "base");
			Path ignore = directory.resolve(".gitignore");
			Files.writeString(ignore, "");
			git.add().addFilepattern(".").call();
			// Establish indexed descendants before excluding their parent.
			Files.writeString(ignore, "tracked/\n");
			git.add().addFilepattern(".gitignore").call();
			git.commit()
					.setAuthor("Traversal Test", "traversal@example.invalid")
					.setCommitter("Traversal Test", "traversal@example.invalid")
					.setMessage("base").call();
			Files.writeString(directory.resolve("tracked/hidden"), "ignored");
			fixture.getRepository().getConfig().setBoolean("jgit", null,
					"fsmonitorCache", false);
			entry = new IndexDiffCacheEntry(fixture.getRepository(), null);
			waitForJobs();
			assertNotNull(entry.getIndexDiff());
			assertTrue(entry.getIndexDiff().getModified().isEmpty());
			assertEquals(Set.of("tracked/hidden"),
					entry.getIndexDiff().getIgnoredNotInIndex());
			Files.writeString(directory.resolve("tracked/deep/file"),
					"modified content");
			Files.writeString(directory.resolve("tracked/more-hidden"),
					"ignored");
			Files.createDirectories(directory.resolve("visible"));
			Files.writeString(directory.resolve("visible/file"), "new");
			if (incremental) {
				entry.refreshFiles(List.of("tracked/deep/file",
						"tracked/more-hidden", "visible/file"));
			} else {
				entry.refresh();
			}
			waitForJobs();
			IndexDiffData data = entry.getIndexDiff();
			assertEquals(Set.of("tracked/deep/file"), data.getModified());
			assertEquals(Set.of("tracked/hidden", "tracked/more-hidden"),
					data.getIgnoredNotInIndex());
			assertEquals(Set.of("visible/file"), data.getUntracked());
			assertEquals(Set.of("visible/"), data.getUntrackedFolders());
			assertTrue(data.getAdded().isEmpty());
			assertTrue(data.getChanged().isEmpty());
			assertTrue(data.getMissing().isEmpty());
			assertTrue(data.getRemoved().isEmpty());
			assertTrue(data.getConflicting().isEmpty());
		} finally {
			if (entry != null) {
				entry.dispose();
				waitForJobs();
			}
			fixture.dispose();
			FileUtils.delete(directory.toFile(),
					FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}
}
