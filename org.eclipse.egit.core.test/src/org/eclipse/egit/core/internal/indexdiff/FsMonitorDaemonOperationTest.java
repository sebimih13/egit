/*******************************************************************************
 * Copyright (C) 2026, and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeFsMonitorDaemonSupport;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.junit.Test;

/**
 * Native t7527 queries and flush/failure transitions through retained EGit
 * jobs.
 */
public class FsMonitorDaemonOperationTest extends GitTestCase {
	@Test
	public void retainedRefreshAndStagingPreserveDaemonNotifications()
			throws Exception {
		check(false);
	}

	@Test
	public void daemonBackedCompleteRefreshPublishesAndInvalidatesUntrackedCache()
			throws Exception {
		check(true);
	}

	private void check(boolean publish) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepareWithoutExtension(repository);
			try (var git = Git.wrap(repository)) {
				git.add().addFilepattern(".").call();
				git.commit().setAuthor("Daemon Test", "daemon@example.invalid")
						.setCommitter("Daemon Test", "daemon@example.invalid")
						.setMessage("project").call();
			}
			repository.getConfig().setBoolean("core", null, "untrackedCache",
					true);
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					!publish);
			repository.getConfig().setString("status", null,
					"showUntrackedFiles", "all");
			try (var daemon = NativeFsMonitorDaemonSupport.start(repository)) {
				var nativeIndex = NativeFsMonitorIndexSupport.mutate(repository,
						"update-index", "--fsmonitor", "--fsmonitor-valid", "a",
						"m", "z", ".project", "--force-write-index");
				Files.copy(nativeIndex.index(),
						repository.getIndexFile().toPath(),
						StandardCopyOption.REPLACE_EXISTING);
				cache = new IndexDiffCacheEntry(repository, null, false,
						publish);
				waitForJobs();
				var first = cache.getIndexDiff();
				assertNotNull(first);
				assertTrue(first.getModified().isEmpty());
				assertTrue(first.getUntracked().isEmpty());
				cache.refresh();
				waitForJobs();
				if (!publish)
					assertSame(first, cache.getIndexDiff());
				if (publish) {
					assertTrue(repository.readDirCache()
							.getUntrackedCache().root.valid);
				}
				var file = repository.getWorkTree().toPath().resolve("a");
				var time = Files.getLastModifiedTime(file);
				TimeUnit.MILLISECONDS
						.sleep(1010 - System.currentTimeMillis() % 1000);
				Files.writeString(file, "edit\n");
				Files.setLastModifiedTime(file, time);
				Files.writeString(
						repository.getWorkTree().toPath().resolve("loose"),
						"new\n");
				NativeFsMonitorWorkloadSupport.assertNativeDiff(repository,
						false, null, Set.of("a"));
				cache.refresh();
				waitForJobs();
				assertEquals(Set.of("a"), cache.getIndexDiff().getModified());
				assertEquals(Set.of("loose"),
						cache.getIndexDiff().getUntracked());
				if (publish) {
					// The writer queries the persisted pre-scan clock, so the
					// overlapping notification conservatively invalidates this
					// directory. A quiescent scan must then converge.
					assertFalse(repository.readDirCache()
							.getUntrackedCache().root.valid);
					cache.refresh();
					waitForJobs();
					assertTrue(repository.readDirCache()
							.getUntrackedCache().root.valid);
					assertEquals(Set.of("loose"),
							cache.getIndexDiff().getUntracked());
				}
				project.getProject().refreshLocal(IResource.DEPTH_INFINITE,
						null);
				new AddToIndexOperation(new IResource[] {
						project.getProject().getFile("loose") }).execute(null);
				waitForJobs();
				cache.refresh();
				waitForJobs();
				assertEquals(Set.of("loose"), cache.getIndexDiff().getAdded());
				assertEquals(Set.of("a"), cache.getIndexDiff().getModified());
				var index = repository.readDirCache();
				assertFalse(index.isFsMonitorValid(index.findEntry("a")));
				daemon.stop();
				var other = repository.getWorkTree().toPath().resolve("z");
				var otherTime = Files.getLastModifiedTime(other);
				Files.writeString(other, "edit\n");
				Files.setLastModifiedTime(other, otherTime);
				cache.refresh();
				waitForJobs();
				assertEquals(Set.of("a", "z"),
						cache.getIndexDiff().getModified());
				assertEquals(Set.of("loose"), cache.getIndexDiff().getAdded());
			}
		} finally {
			if (cache != null)
				cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}
}
