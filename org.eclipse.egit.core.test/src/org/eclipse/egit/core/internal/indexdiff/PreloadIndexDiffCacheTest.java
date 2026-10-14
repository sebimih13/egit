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

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

public class PreloadIndexDiffCacheTest extends GitTestCase {
	@Test
	public void fullRefreshUsesPreloadWithoutChangingResults()
			throws Exception {
		TestRepository fixture = new TestRepository(gitDir);
		Repository repository = fixture.getRepository();
		IndexDiffCacheEntry entry = null;
		try (Git git = Git.wrap(repository)) {
			Path directory = repository.getWorkTree().toPath()
					.resolve("tracked");
			Files.createDirectories(directory);
			for (int i = 0; i < 1000; i++)
				Files.writeString(directory.resolve("f" + i), "base\n");
			git.add().addFilepattern(".").call();
			git.commit().setAuthor("Preload Test", "preload@example.invalid")
					.setCommitter("Preload Test", "preload@example.invalid")
					.setMessage("base").call();
			repository.getConfig().setBoolean("core", null, "preloadIndex",
					true);
			entry = new IndexDiffCacheEntry(repository, null);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertNotNull(entry.getIndexDiff());
			assertTrue(entry.getIndexDiff().getModified().isEmpty());
			Files.writeString(directory.resolve("f0"), "changed\n");
			Files.delete(directory.resolve("f1"));
			Files.writeString(directory.resolve("new"), "new");
			entry.refresh();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			IndexDiffData refreshed = entry.getIndexDiff();
			assertTrue(refreshed.getModified().contains("tracked/f0"));
			assertTrue(refreshed.getMissing().contains("tracked/f1"));
			assertTrue(refreshed.getUntracked().contains("tracked/new"));
			repository.getConfig().setBoolean("core", null, "preloadIndex",
					false);
			Status serial = git.status().call();
			assertEquals(serial.getModified(), refreshed.getModified());
			assertEquals(serial.getMissing(), refreshed.getMissing());
			assertEquals(serial.getUntracked(), refreshed.getUntracked());
			repository.getConfig().setBoolean("core", null, "preloadIndex",
					true);
			Files.writeString(directory.resolve("f0"), "base\n");
			entry.refresh();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertTrue(entry.getIndexDiff().getModified().isEmpty());
		} finally {
			if (entry != null) {
				entry.dispose();
				TestUtils.waitForJobs(10000,
						JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			}
			fixture.dispose();
		}
	}
}
