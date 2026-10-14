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
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Test;

public class DirectoryMetadataIndexDiffCacheTest extends GitTestCase {
	@Test
	public void wideSymlinkDirectoryRefreshesWithoutFollowingReferences()
			throws Exception {
		TestRepository fixture = new TestRepository(gitDir);
		Repository repository = fixture.getRepository();
		IndexDiffCacheEntry entry = null;
		try (Git git = Git.wrap(repository)) {
			assumeTrue(repository.getFS() instanceof FS_POSIX);
			Path directory = repository.getWorkTree().toPath()
					.resolve("references");
			Files.createDirectories(directory);
			for (int i = 0; i < 1500; i++)
				Files.createSymbolicLink(directory.resolve("ref" + i),
						Path.of("missing-test-reference"));
			git.add().addFilepattern(".").call();
			git.commit()
					.setAuthor("Directory Test", "directory@example.invalid")
					.setCommitter("Directory Test", "directory@example.invalid")
					.setMessage("references").call();
			repository.getConfig().setBoolean("core", null, "preloadIndex",
					false);
			entry = new IndexDiffCacheEntry(repository, null);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertTrue(entry.getIndexDiff().getModified().isEmpty());
			Files.delete(directory.resolve("ref0"));
			Files.createSymbolicLink(directory.resolve("ref0"),
					Path.of("changed-reference"));
			Files.delete(directory.resolve("ref1"));
			Files.createSymbolicLink(directory.resolve("new"),
					Path.of("another-reference"));
			entry.refresh();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			IndexDiffData refreshed = entry.getIndexDiff();
			assertEquals(Set.of("references/ref0"), refreshed.getModified());
			assertEquals(Set.of("references/ref1"), refreshed.getMissing());
			assertEquals(Set.of("references/new"), refreshed.getUntracked());
			assertEquals(Path.of("changed-reference"),
					Files.readSymbolicLink(directory.resolve("ref0")));
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
