/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.FsMonitor;
import org.junit.Test;

/** Keep provider deltas separate from cached-result validity after checkout. */
public class FsMonitorCheckoutRefreshScopeTest extends GitTestCase {
	@Test
	public void checkoutRefreshKeepsReportedScopeAndRebuildsResult()
			throws Exception {
		check(false);
	}

	@Test
	public void earlierDirtySameStatPathSurvivesScopedCheckoutRefresh()
			throws Exception {
		check(true);
	}

	private static void check(boolean dirty) throws Exception {
		var directory = Files
				.createTempDirectory("egit-checkout-refresh-scope-");
		TestRepository fixture = new TestRepository(
				directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		boolean suspended = false;
		try {
			var repository = fixture.getRepository();
			var root = repository.getWorkTree().toPath();
			Files.writeString(root.resolve("tracked"), "base");
			Files.writeString(root.resolve("guard"), "base");
			org.eclipse.jgit.revwalk.RevCommit base;
			org.eclipse.jgit.revwalk.RevCommit target;
			try (Git git = Git.wrap(repository)) {
				git.add().addFilepattern(".").call();
				base = git.commit().setMessage("base")
						.setAuthor("Scope Test", "scope@example.invalid")
						.setCommitter("Scope Test", "scope@example.invalid")
						.call();
				Files.writeString(root.resolve("tracked"), "next");
				git.add().addFilepattern("tracked").call();
				target = git.commit().setMessage("target")
						.setAuthor("Scope Test", "scope@example.invalid")
						.setCommitter("Scope Test", "scope@example.invalid")
						.call();
				git.checkout().setName(base.name()).call();
			}
			var clock = repository.getDirectory().toPath()
					.resolve("scope-clock");
			var paths = repository.getDirectory().toPath()
					.resolve("scope-paths");
			var hook = repository.getDirectory().toPath()
					.resolve("scope-monitor");
			Files.writeString(hook,
					"#!/bin/sh\nread clock < .git/scope-clock\nprintf '%s\\000' \"$clock\"\n"
							+ "if test \"$2\" != \"$clock\"; then cat .git/scope-paths; fi\n");
			assertTrue(hook.toFile().setExecutable(true));
			Files.writeString(clock, "before\n");
			Files.write(paths,
					dirty ? "guard\0".getBytes(StandardCharsets.UTF_8)
							: new byte[0]);
			var config = repository.getConfig();
			config.setString("core", null, "fsmonitor", hook.toString());
			config.setInt("core", null, "fsmonitorHookVersion", 2);
			config.setBoolean("core", null, "untrackedCache", true);
			config.setBoolean("feature", null, "manyFiles", true);
			config.setBoolean("jgit", null, "fsmonitorCache", true);
			config.save();
			if (dirty) {
				var mtime = Files.getLastModifiedTime(root.resolve("guard"));
				long now = System.currentTimeMillis();
				TimeUnit.MILLISECONDS.sleep(1010 - now % 1000);
				Files.writeString(root.resolve("guard"), "edit");
				Files.setLastModifiedTime(root.resolve("guard"), mtime);
			}
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			assertEquals(dirty ? Set.of("guard") : Set.of(),
					entry.getIndexDiff().getModified());
			Job.getJobManager().suspend();
			suspended = true;
			Files.writeString(clock, "after\n");
			Files.write(paths, "tracked\0".getBytes(StandardCharsets.UTF_8));
			BranchOperation operation = new BranchOperation(repository,
					target.name());
			operation.execute(null);
			assertEquals(CheckoutResult.Status.OK,
					operation.getResult(repository).getStatus());
			Method calculate = IndexDiffCacheEntry.class.getDeclaredMethod(
					"calcIndexDiffDataFull", IProgressMonitor.class,
					String.class, org.eclipse.jgit.lib.Repository.class);
			calculate.setAccessible(true);
			IndexDiffData data = (IndexDiffData) calculate.invoke(entry,
					new NullProgressMonitor(), "checkout scope", repository);
			Field pending = IndexDiffCacheEntry.class
					.getDeclaredField("pendingFsMonitorProbe");
			pending.setAccessible(true);
			FsMonitor.Probe probe = (FsMonitor.Probe) pending.get(entry);
			assertNotNull(probe);
			assertEquals(Set.of("tracked"),
					Set.copyOf(probe.getChangedPaths()));
			assertEquals(dirty ? Set.of("guard") : Set.of(),
					data.getModified());
			assertTrue(data.getAdded().isEmpty());
			assertTrue(data.getChanged().isEmpty());
			assertTrue(data.getRemoved().isEmpty());
			assertTrue(data.getMissing().isEmpty());
			assertTrue(data.getUntracked().isEmpty());
			assertTrue(data.getConflicting().isEmpty());
			assertArrayEquals(
					dirty ? " M guard\0".getBytes(StandardCharsets.UTF_8)
							: new byte[0],
					NativeFsMonitorIndexSupport.status(repository, false));
			Method publish = IndexDiffCacheEntry.class.getDeclaredMethod(
					"publishIndexDiffData", IndexDiffData.class,
					IProgressMonitor.class);
			publish.setAccessible(true);
			assertTrue((Boolean) publish.invoke(entry, data,
					new NullProgressMonitor()));
			IndexDiffData quiet = (IndexDiffData) calculate.invoke(entry,
					new NullProgressMonitor(), "quiet scope", repository);
			assertSame(data, quiet);
			config.setBoolean("core", null, "fileMode", false);
			config.save();
			IndexDiffData configData = (IndexDiffData) calculate.invoke(entry,
					new NullProgressMonitor(), "config scope", repository);
			probe = (FsMonitor.Probe) pending.get(entry);
			assertNotNull(probe);
			assertEquals(java.util.List.of("/"), probe.getChangedPaths());

			assertTrue((Boolean) publish.invoke(entry, configData,
					new NullProgressMonitor()));
			Files.writeString(root.resolve(".gitattributes"),
					"tracked -text\n");
			try (Git git = Git.wrap(repository)) {
				git.add().addFilepattern(".gitattributes").call();
			}
			IndexDiffData ruleData = (IndexDiffData) calculate.invoke(entry,
					new NullProgressMonitor(), "indexed rule scope",
					repository);
			probe = (FsMonitor.Probe) pending.get(entry);
			assertNotNull(probe);
			assertEquals(java.util.List.of("/"), probe.getChangedPaths());
			assertEquals(Set.of(".gitattributes"), ruleData.getAdded());
			assertArrayEquals(
					("A  .gitattributes\0" + (dirty ? " M guard\0" : ""))
							.getBytes(StandardCharsets.UTF_8),
					NativeFsMonitorIndexSupport.status(repository, false));

		} finally {
			if (entry != null)
				entry.dispose();
			if (suspended)
				Job.getJobManager().resume();
			waitForJobs();
			fixture.dispose();
			FileUtils.delete(directory.toFile(),
					FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}
}
