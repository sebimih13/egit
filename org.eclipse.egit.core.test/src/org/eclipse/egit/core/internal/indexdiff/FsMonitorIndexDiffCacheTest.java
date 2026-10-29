/*******************************************************************************
 * Copyright (C) 2026, and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;

public class FsMonitorIndexDiffCacheTest extends GitTestCase {
	@Test
	public void versionOneAndAutoFallbackRevalidateStagingRefresh()
			throws Exception {
		String script = "[ \"$1\" = 1 ] || exit 1\n"
				+ "case \"$2\" in *[!0-9]*|'') exit 2;; esac\n"
				+ "if [ -f .git/monitor-changed ]; then echo /; fi\n";
		for (boolean explicit : new boolean[] { true, false }) {
			try (MonitoredFixture f = new MonitoredFixture(script)) {
				if (explicit)
					f.repository.getConfig().setInt("core", null,
							"fsmonitorHookVersion", 1);
				stagingRefresh(f.entry);
				IndexDiffData first = f.entry.getIndexDiff();
				stagingRefresh(f.entry);
				assertSame(first, f.entry.getIndexDiff());
				f.change();
				stagingRefresh(f.entry);
				IndexDiffData changed = f.entry.getIndexDiff();
				assertNotSame(first, changed);
				assertTrue(changed.getUntracked().contains("monitor-new"));
				Files.delete(f.repository.getDirectory().toPath()
						.resolve("monitor-changed"));
				stagingRefresh(f.entry);
				assertSame(changed, f.entry.getIndexDiff());
				// Force v2: the v1-only provider now fails, requiring a scan.
				f.repository.getConfig().setInt("core", null,
						"fsmonitorHookVersion", 2);
				Files.delete(f.isolatedDirectory.resolve("monitor-new"));
				stagingRefresh(f.entry);
				assertFalse(f.entry.getIndexDiff().getUntracked()
						.contains("monitor-new"));
			}
		}
	}

	@Test
	public void untrackedCachePolicyMatchesNativeConfigurationAndRefresh()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(inclusiveHook())) {
			Files.writeString(f.isolatedDirectory.resolve("loose"),
					"untracked");
			for (boolean manyFiles : new boolean[] { false, true }) {
				for (boolean existing : new boolean[] { false, true }) {
					for (String value : org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport.UNTRACKED_SETTINGS) {
						boolean expected = org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport
								.nativeUntrackedCachePolicy(f.repository,
										manyFiles, value, existing);
						f.repository.getConfig().setBoolean("feature", null,
								"manyFiles", manyFiles);
						if (value == null)
							f.repository.getConfig().unset("core", null,
									"untrackedCache");
						else
							f.repository.getConfig().setString("core", null,
									"untrackedCache", value);
						String row = "manyFiles=" + manyFiles + ", existing="
								+ existing + ", value=" + value;
						org.junit.Assert.assertEquals(row, expected,
								org.eclipse.jgit.util.FsMonitor
										.isUntrackedCacheEnabled(f.repository,
												null));
						stagingRefresh(f.entry);
						IndexDiffData first = f.entry.getIndexDiff();
						stagingRefresh(f.entry);
						IndexDiffData second = f.entry.getIndexDiff();
						org.junit.Assert.assertEquals(row,
								java.util.Set.of("loose"),
								second.getUntracked());
						if (expected)
							assertSame(row, first, second);
						else
							assertNotSame(row, first, second);
					}
				}
			}
		}
	}

	@Test
	public void stagingRefreshJobKeepsTracingOpenAndRevalidatesMonitorState()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(inclusiveHook())) {
			org.eclipse.jgit.util.Trace2 trace = org.eclipse.jgit.util.Trace2
					.getInstance();
			boolean tracing = trace.isEnabled();
			IndexDiffData initial = f.entry.getIndexDiff();
			stagingRefresh(f.entry);
			assertSame(initial, f.entry.getIndexDiff());
			f.change();
			stagingRefresh(f.entry);
			IndexDiffData changed = f.entry.getIndexDiff();
			assertNotSame(initial, changed);
			assertTrue(changed.getUntracked().contains("monitor-new"));
			stagingRefresh(f.entry);
			assertSame(changed, f.entry.getIndexDiff());
			org.junit.Assert.assertSame(trace,
					org.eclipse.jgit.util.Trace2.getInstance());
			org.junit.Assert.assertEquals("Refresh must not end the library session",
					tracing, trace.isEnabled());
		}
	}

	private static void stagingRefresh(IndexDiffCacheEntry entry)
			throws Exception {
		// This is the exact job factory invoked by StagingView.refreshAction.
		Job refresh = entry.createRefreshResourcesAndIndexDiffJob();
		refresh.schedule();
		assertTrue("Staging refresh must finish", refresh.join(10000, null));
		assertTrue(refresh.getResult().toString(), refresh.getResult().isOK());
		waitForJobs();
	}

	@Test
	public void missingHookFallbackRetainsRestoredTimestampChanges()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(trackedHook());
				org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(
						f.repository)) {
			Path tracked = f.repository.getWorkTree().toPath().resolve("tracked");
			Files.writeString(tracked, "one");
			java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(tracked, time);
			git.add().addFilepattern("tracked").call();
			git.commit().setAuthor("Monitor Test", "monitor@example.invalid")
					.setCommitter("Monitor Test", "monitor@example.invalid")
					.setMessage("base").call();
			waitForJobs();
			f.entry.refresh();
			waitForJobs();
			assertTrue(f.entry.getIndexDiff().getModified().isEmpty());
			Files.writeString(tracked, "two");
			Files.setLastModifiedTime(tracked, time);
			Files.delete(f.hook);
			for (int i = 0; i < 2; i++) {
				f.entry.refresh();
				waitForJobs();
				org.junit.Assert.assertEquals(java.util.Set.of("tracked"),
						f.entry.getIndexDiff().getModified());
			}
			Files.writeString(f.repository.getWorkTree().toPath().resolve("other"),
					"untracked");
			f.entry.refreshFiles(List.of("other"));
			waitForJobs();
			f.entry.refresh();
			waitForJobs();
			org.junit.Assert.assertEquals("Incremental invalidation must retain the content-check obligation",
					java.util.Set.of("tracked"), f.entry.getIndexDiff().getModified());
		}
	}

	@Test
	public void canceledReloadDoesNotIncreaseParallelism() throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(inclusiveHook())) {
			Field jobs = IndexDiffCacheEntry.class.getDeclaredField("reloadJob");
			jobs.setAccessible(true);
			Object job = jobs.get(f.entry);
			Method reload = job.getClass().getDeclaredMethod("reload",
					IProgressMonitor.class);
			reload.setAccessible(true);
			Field permits = IndexDiffCacheEntry.class.getDeclaredField("parallelism");
			permits.setAccessible(true);
			java.util.concurrent.Semaphore semaphore = (java.util.concurrent.Semaphore) permits
					.get(null);
			int before = semaphore.availablePermits();
			NullProgressMonitor canceled = new NullProgressMonitor();
			canceled.setCanceled(true);
			try {
				reload.invoke(job, canceled);
				org.junit.Assert.assertEquals(before, semaphore.availablePermits());
			} finally {
				// Keep a failing baseline reproduction from changing other tests.
				int extra = semaphore.availablePermits() - before;
				if (extra > 0) {
					semaphore.acquire(extra);
				}
			}
		}
	}

	@Test
	public void keepCacheHitReusesValidatedIndexAndRemovalRescansUntracked()
			throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		TestRepository fixture = new TestRepository(gitDir);
		Repository source = fixture.getRepository();
		Path response = source.getDirectory().toPath()
				.resolve("monitor-response");
		Path hook = source.getDirectory().toPath()
				.resolve("hooks/fsmonitor-test");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook, "#!/bin/sh\ncat '"
				+ response.toString().replace("'", "'\\''") + "'\n");
		assertTrue(hook.toFile().setExecutable(true));
		Files.writeString(response, "initial\0/\0");
		source.getConfig().setString("core", null, "fsmonitor",
				hook.toString());
		source.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		source.getConfig().setString("core", null, "untrackedCache", "keep");
		source.getConfig().save();
		org.eclipse.jgit.dircache.DirCache index = source.lockDirCache();
		try {
			index.setUntrackedCache(
					new org.eclipse.jgit.internal.storage.file.UntrackedCache());
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
		}
		java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
		IndexDiffCacheEntry entry = null;
		try (Repository repository = new org.eclipse.jgit.internal.storage.file.FileRepository(
				gitDir) {
			@Override
			public org.eclipse.jgit.dircache.DirCache readDirCache()
					throws java.io.IOException {
				reads.incrementAndGet();
				return super.readDirCache();
			}
		}) {
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			IndexDiffData initial = entry.getIndexDiff();
			assertNotNull(initial);
			Files.writeString(response, "stable\0");
			int before = reads.get();
			for (int i = 0; i < 3; i++) {
				entry.refresh();
				waitForJobs();
				assertSame(initial, entry.getIndexDiff());
			}
			org.junit.Assert.assertEquals(
					"Unchanged refresh must not parse the index again", before,
					reads.get());
			index = repository.lockDirCache();
			try {
				index.setUntrackedCache(null);
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			Files.writeString(
					repository.getWorkTree().toPath().resolve("unreported"),
					"new");
			entry.refresh();
			waitForJobs();
			assertTrue(
					entry.getIndexDiff().getUntracked().contains("unreported"));
		} finally {
			if (entry != null) {
				entry.dispose();
			}
			fixture.dispose();
		}
	}

	@Test
	public void unchangedRefreshReusesAndIncrementalUpdatesInvalidate()
			throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		TestRepository fixture = new TestRepository(gitDir);
		Repository repository = fixture.getRepository();
		IndexDiffCacheEntry entry = null;
		try {
			Path response = repository.getDirectory().toPath()
					.resolve("monitor-response");
			Path hook = repository.getDirectory().toPath()
					.resolve("hooks/fsmonitor-test");
			Files.createDirectories(hook.getParent());
			Files.writeString(hook, "#!/bin/sh\ncat '"
					+ response.toString().replace("'", "'\\''") + "'\n");
			assertTrue(hook.toFile().setExecutable(true));
			Files.writeString(response, "initial\0/\0");
			repository.getConfig().setString("core", null, "fsmonitor",
					hook.toString());
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					true);
			repository.getConfig().setBoolean("core", null, "untrackedCache", true);
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			IndexDiffData initial = entry.getIndexDiff();
			assertNotNull(initial);
			Files.writeString(response, "next\0");
			entry.refresh();
			waitForJobs();
			assertSame(initial, entry.getIndexDiff());
			Path changed = repository.getWorkTree().toPath()
					.resolve("monitor-new");
			Files.writeString(changed, "new");
			Files.writeString(response, "changed\0monitor-new\0");
			entry.refresh();
			waitForJobs();
			IndexDiffData updated = entry.getIndexDiff();
			assertNotSame(initial, updated);
			assertTrue(updated.getUntracked().contains("monitor-new"));
			Files.writeString(response, "stable\0");
			entry.refresh();
			waitForJobs();
			assertSame(updated, entry.getIndexDiff());
			Files.delete(changed);
			entry.refreshFiles(List.of("monitor-new"));
			waitForJobs();
			assertFalse(entry.getIndexDiff().getUntracked()
					.contains("monitor-new"));
			IndexDiffData incremental = entry.getIndexDiff();
			entry.refresh();
			waitForJobs();
			assertNotSame(incremental, entry.getIndexDiff());
			Files.writeString(hook, "#!/bin/sh\nexit 1\n");
			IndexDiffData previous = entry.getIndexDiff();
			entry.refresh();
			waitForJobs();
			assertNotSame(previous, entry.getIndexDiff());
		} finally {
			if (entry != null) {
				entry.dispose();
				waitForJobs();
			}
			fixture.dispose();
		}
	}

	@Test
	public void unpublishedScanMustNotAdvancePublishedResultsToken()
			throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		TestRepository fixture = new TestRepository(gitDir);
		Repository repository = fixture.getRepository();
		IndexDiffCacheEntry entry = null;
		try {
			Path hook = repository.getDirectory().toPath()
					.resolve("hooks/fsmonitor-inclusive");
			Files.createDirectories(hook.getParent());
			// Every reply includes changes since the supplied token, including
			// changes
			// whose scan a caller abandoned before publishing its result.
			Files.writeString(hook, "#!/bin/sh\n"
					+ "if [ ! -f .git/monitor-changed ]; then printf 'initial\\0';\n"
					+ "elif [ \"$2\" = changed ]; then printf 'changed\\0';\n"
					+ "else printf 'changed\\0monitor-new\\0'; fi\n");
			assertTrue(hook.toFile().setExecutable(true));
			repository.getConfig().setString("core", null, "fsmonitor",
					hook.toString());
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					true);
			repository.getConfig().setBoolean("core", null, "untrackedCache", true);
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			IndexDiffData initial = entry.getIndexDiff();
			assertNotNull(initial);
			Files.writeString(
					repository.getWorkTree().toPath().resolve("monitor-new"),
					"new");
			Files.writeString(repository.getDirectory().toPath()
					.resolve("monitor-changed"), "changed");
			Method calculate = IndexDiffCacheEntry.class.getDeclaredMethod(
					"calcIndexDiffDataFull", IProgressMonitor.class,
					String.class, Repository.class);
			calculate.setAccessible(true);
			IndexDiffData unpublished = (IndexDiffData) calculate.invoke(entry,
					new NullProgressMonitor(), "abandoned scan", repository);
			assertTrue(unpublished.getUntracked().contains("monitor-new"));
			assertSame(initial, entry.getIndexDiff());
			entry.refresh();
			waitForJobs();
			assertTrue(entry.getIndexDiff().getUntracked()
					.contains("monitor-new"));
			assertNotSame(initial, entry.getIndexDiff());
		} finally {
			if (entry != null) {
				entry.dispose();
				waitForJobs();
			}
			fixture.dispose();
		}
	}

	private static String inclusiveHook() {
		return "if [ ! -f .git/monitor-changed ]; then printf 'initial\\0';\n"
				+ "elif [ \"$2\" = changed ]; then printf 'changed\\0';\n"
				+ "else printf 'changed\\0monitor-new\\0'; fi\n";
	}

	private final class MonitoredFixture implements AutoCloseable {
		final TestRepository fixture;

		final Path isolatedDirectory;

		final Repository repository;

		final Path hook;

		final IndexDiffCacheEntry entry;

		MonitoredFixture(String script) throws Exception {
			assumeFalse(SystemReader.getInstance().isWindows());
			isolatedDirectory = Files
					.createTempDirectory("egit-fsmonitor-lifecycle-");
			fixture = new TestRepository(
					isolatedDirectory.resolve(".git").toFile());
			repository = fixture.getRepository();
			hook = repository.getDirectory().toPath()
					.resolve("hooks/fsmonitor-lifecycle");
			Files.createDirectories(hook.getParent());
			Files.writeString(hook, "#!/bin/sh\n" + script);
			assertTrue(hook.toFile().setExecutable(true));
			repository.getConfig().setString("core", null, "fsmonitor",
					hook.toString());
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					true);
			repository.getConfig().setBoolean("core", null, "untrackedCache", true);
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			assertNotNull(entry.getIndexDiff());
		}

		void change() throws Exception {
			Files.writeString(
					repository.getWorkTree().toPath().resolve("monitor-new"),
					"new");
			Files.writeString(repository.getDirectory().toPath()
					.resolve("monitor-changed"), "changed");
		}

		IndexDiffData calculate() throws Exception {
			Method calculate = IndexDiffCacheEntry.class.getDeclaredMethod(
					"calcIndexDiffDataFull", IProgressMonitor.class,
					String.class, Repository.class);
			calculate.setAccessible(true);
			return (IndexDiffData) calculate.invoke(entry,
					new NullProgressMonitor(), "test scan", repository);
		}

		@Override
		public void close() throws Exception {
			try {
				entry.dispose();
				waitForJobs();
			} finally {
				fixture.dispose();
				org.eclipse.jgit.util.FileUtils.delete(
						isolatedDirectory.toFile(),
						org.eclipse.jgit.util.FileUtils.RECURSIVE
								| org.eclipse.jgit.util.FileUtils.RETRY);
			}
		}
	}

	@Test
	public void cancelledPublicationClearsPendingToken() throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(inclusiveHook())) {
			IndexDiffData initial = f.entry.getIndexDiff();
			f.change();
			IndexDiffData pending = f.calculate();
			assertTrue(pending.getUntracked().contains("monitor-new"));
			NullProgressMonitor cancelled = new NullProgressMonitor();
			cancelled.setCanceled(true);
			Method publish = IndexDiffCacheEntry.class.getDeclaredMethod(
					"publishIndexDiffData", IndexDiffData.class,
					IProgressMonitor.class);
			publish.setAccessible(true);
			assertFalse(((Boolean) publish.invoke(f.entry, pending, cancelled))
					.booleanValue());
			assertSame(initial, f.entry.getIndexDiff());
			f.entry.refresh();
			waitForJobs();
			IndexDiffData updated = f.entry.getIndexDiff();
			assertTrue(updated.getUntracked().contains("monitor-new"));
			f.entry.refresh();
			waitForJobs();
			assertSame(updated, f.entry.getIndexDiff());
		}
	}

	@Test
	public void cancellingRunningReloadRetainsPublishedDataAndNextRefreshConverges()
			throws Exception {
		String gate = "if [ -f .git/gate-enabled ]; then touch .git/hook-ready;\n"
				+ "while [ ! -f .git/hook-release ]; do sleep 0.01; done; fi\n";
		try (MonitoredFixture f = new MonitoredFixture(
				gate + inclusiveHook())) {
			IndexDiffData initial = f.entry.getIndexDiff();
			f.change();
			Path metadata = f.repository.getDirectory().toPath();
			Files.writeString(metadata.resolve("gate-enabled"), "gate");
			f.entry.refresh();
			try {
				long deadline = System.nanoTime() + 3_000_000_000L;
				while (!Files.exists(metadata.resolve("hook-ready"))
						&& System.nanoTime() < deadline) {
					Thread.sleep(10);
				}
				assertTrue(Files.exists(metadata.resolve("hook-ready")));
				Field reload = IndexDiffCacheEntry.class
						.getDeclaredField("reloadJob");
				reload.setAccessible(true);
				Job job = (Job) reload.get(f.entry);
				assertNotNull(job);
				job.cancel();
			} finally {
				Files.writeString(metadata.resolve("hook-release"), "release");
			}
			waitForJobs();
			assertSame(initial, f.entry.getIndexDiff());
			f.entry.refresh();
			waitForJobs();
			assertTrue(f.entry.getIndexDiff().getUntracked()
					.contains("monitor-new"));
		}
	}

	@Test
	public void disposePreventsLatePublicationFromResurrectingCachedData()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(inclusiveHook())) {
			f.change();
			IndexDiffData pending = f.calculate();
			f.entry.dispose();
			Method publish = IndexDiffCacheEntry.class.getDeclaredMethod(
					"publishIndexDiffData", IndexDiffData.class,
					IProgressMonitor.class);
			publish.setAccessible(true);
			assertFalse(((Boolean) publish.invoke(f.entry, pending,
					new NullProgressMonitor())).booleanValue());
			org.junit.Assert.assertNull(f.entry.getIndexDiff());
		}
	}

	@Test
	public void recrawlMalformedAndDisabledMonitorNeverReusePublishedResults()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(inclusiveHook())) {
			for (String script : new String[] { "printf 'recrawl\\0/\\0'\n",
					"printf 'bad\\0../outside\\0'\n", "exit 1\n" }) {
				IndexDiffData previous = f.entry.getIndexDiff();
				Files.writeString(f.hook, "#!/bin/sh\n" + script);
				f.entry.refresh();
				waitForJobs();
				assertNotSame(previous, f.entry.getIndexDiff());
			}
			Files.writeString(f.hook, "#!/bin/sh\n" + inclusiveHook());
			f.entry.refresh();
			waitForJobs();
			IndexDiffData seeded = f.entry.getIndexDiff();
			f.entry.refresh();
			waitForJobs();
			assertSame(seeded, f.entry.getIndexDiff());
			f.repository.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					false);
			f.change();
			f.entry.refresh();
			waitForJobs();
			assertNotSame(seeded, f.entry.getIndexDiff());
			assertTrue(f.entry.getIndexDiff().getUntracked()
					.contains("monitor-new"));
		}
	}

	private static String trackedHook() {
		return inclusiveHook().replace("monitor-new", "tracked");
	}

	private static void equalData(IndexDiffData expected,
			IndexDiffData actual) {
		org.junit.Assert.assertEquals(expected.getAdded(), actual.getAdded());
		org.junit.Assert.assertEquals(expected.getChanged(),
				actual.getChanged());
		org.junit.Assert.assertEquals(expected.getRemoved(),
				actual.getRemoved());
		org.junit.Assert.assertEquals(expected.getMissing(),
				actual.getMissing());
		org.junit.Assert.assertEquals(expected.getModified(),
				actual.getModified());
		org.junit.Assert.assertEquals(expected.getUntracked(),
				actual.getUntracked());
		org.junit.Assert.assertEquals(expected.getUntrackedFolders(),
				actual.getUntrackedFolders());
		org.junit.Assert.assertEquals(expected.getIgnoredNotInIndex(),
				actual.getIgnoredNotInIndex());
		org.junit.Assert.assertEquals(expected.getConflicting(),
				actual.getConflicting());
		org.junit.Assert.assertEquals(expected.getConflictStates(),
				actual.getConflictStates());
		org.junit.Assert.assertEquals(expected.getAssumeUnchanged(),
				actual.getAssumeUnchanged());
		org.junit.Assert.assertEquals(expected.getSymlinks(),
				actual.getSymlinks());
		org.junit.Assert.assertEquals(expected.getSubmodules(),
				actual.getSubmodules());
	}

	private void restoredTimestampEdit(boolean incremental) throws Exception {
		restoredTimestampEdit(incremental, false);
	}

	private void restoredTimestampEdit(boolean incremental, boolean failedHook) throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(trackedHook());
				org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(
						f.repository)) {
			Path tracked = f.repository.getWorkTree().toPath()
					.resolve("tracked");
			Files.writeString(tracked, "one");
			java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(tracked, time);
			git.add().addFilepattern("tracked").call();
			git.commit().setAuthor("Monitor Test", "monitor@example.invalid")
					.setCommitter("Monitor Test", "monitor@example.invalid")
					.setMessage("base").call();
			waitForJobs();
			f.entry.refresh();
			waitForJobs();
			IndexDiffData initial = f.entry.getIndexDiff();
			assertTrue(initial.getModified().isEmpty());
			Files.writeString(tracked, "two");
			Files.setLastModifiedTime(tracked, time);
			Files.writeString(f.repository.getDirectory().toPath()
					.resolve("monitor-changed"), "changed");
			if (failedHook) {
				Files.writeString(f.hook, "#!/bin/sh\nexit 1\n");
			}
			if (incremental) {
				f.entry.refreshFiles(List.of("tracked"));
			} else {
				f.entry.refresh();
			}
			waitForJobs();
			IndexDiffData changed = f.entry.getIndexDiff();
			assertNotSame(initial, changed);
			org.junit.Assert.assertEquals(java.util.Set.of("tracked"),
					changed.getModified());
			f.entry.refresh();
			waitForJobs();
			equalData(changed, f.entry.getIndexDiff());
			if (failedHook) {
				Files.writeString(f.hook, "#!/bin/sh\n" + trackedHook());
				f.entry.refresh();
				waitForJobs();
				equalData(changed, f.entry.getIndexDiff());
			}
			IndexDiffData stable = f.entry.getIndexDiff();
			f.entry.refresh();
			waitForJobs();
			assertSame(stable, f.entry.getIndexDiff());
		}
	}

	@Test
	public void fullRefreshHashesNotifiedFilesEvenWithRestoredTimestamp()
			throws Exception {
		restoredTimestampEdit(false);
	}

	@Test
	public void incrementalWorkspaceRefreshHashesFilesEvenWithRestoredTimestamp()
			throws Exception {
		restoredTimestampEdit(true);
	}


	@Test
	public void invalidParentDeltaForcesContentChecksAndDoesNotSeedReuse()
			throws Exception {
		String script = "if [ -f .git/monitor-response ]; then "
				+ "cat .git/monitor-response; else printf 'token\\0'; fi\n";
		try (MonitoredFixture f = new MonitoredFixture(script);
				org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(
						f.repository)) {
			Path tracked = f.repository.getWorkTree().toPath().resolve("tracked");
			Files.writeString(tracked, "one");
			java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(tracked, time);
			git.add().addFilepattern("tracked").call();
			git.commit().setAuthor("Monitor Test", "monitor@example.invalid")
					.setCommitter("Monitor Test", "monitor@example.invalid")
					.setMessage("base").call();
			f.entry.refresh();
			waitForJobs();
			assertTrue(f.entry.getIndexDiff().getModified().isEmpty());
			Files.writeString(tracked, "two");
			Files.setLastModifiedTime(tracked, time);
			Path response = f.repository.getDirectory().toPath()
					.resolve("monitor-response");
			Files.writeString(response, "token\0directory\n/../outside\0");
			f.entry.refresh();
			waitForJobs();
			IndexDiffData fallback = f.entry.getIndexDiff();
			org.junit.Assert.assertEquals(java.util.Set.of("tracked"),
					fallback.getModified());
			Files.writeString(response, "token\0");
			f.entry.refresh();
			waitForJobs();
			IndexDiffData seeded = f.entry.getIndexDiff();
			assertNotSame(fallback, seeded);
			equalData(fallback, seeded);
			f.entry.refresh();
			waitForJobs();
			assertSame(seeded, f.entry.getIndexDiff());
		}
	}

	@Test
	public void failedHookFallbackAlsoChecksRestoredTimestampContents() throws Exception {
		restoredTimestampEdit(false, true);
	}

	private static String gateHook() {
		return "if [ -f .git/gate-enabled ]; then touch .git/hook-ready;\n"
				+ "while [ ! -f .git/hook-release ]; do sleep 0.01; done; fi\n";
	}

	private static void awaitReady(Path metadata) throws Exception {
		long deadline = System.nanoTime() + 3_000_000_000L;
		while (!Files.exists(metadata.resolve("hook-ready"))
				&& System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertTrue("Reload must reach the hook",
				Files.exists(metadata.resolve("hook-ready")));
	}

	@Test
	public void coalescedReloadsPublishOnlyTheConvergedResult()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(
				gateHook() + inclusiveHook())) {
			java.util.List<IndexDiffData> published = new java.util.concurrent.CopyOnWriteArrayList<>();
			f.entry.addIndexDiffChangedListener(
					(repository, data) -> published.add(data));
			Path metadata = f.repository.getDirectory().toPath();
			f.change();
			Files.writeString(metadata.resolve("gate-enabled"), "gate");
			f.entry.refresh();
			try {
				awaitReady(metadata);
				for (int i = 0; i < 20; i++) {
					f.entry.refresh();
				}
			} finally {
				Files.writeString(metadata.resolve("hook-release"), "release");
			}
			waitForJobs();
			org.junit.Assert.assertEquals(1, published.size());
			assertSame(published.get(0), f.entry.getIndexDiff());
			assertTrue(published.get(0).getUntracked().contains("monitor-new"));
			IndexDiffData optimized = f.entry.getIndexDiff();
			f.repository.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					false);
			f.entry.refresh();
			waitForJobs();
			equalData(optimized, f.entry.getIndexDiff());
		}
	}

	@Test
	public void listenersObservePublishedResultsAndCanRequestAnotherRefresh()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(inclusiveHook())) {
			java.util.List<IndexDiffData> published = new java.util.concurrent.CopyOnWriteArrayList<>();
			java.util.concurrent.atomic.AtomicBoolean again = new java.util.concurrent.atomic.AtomicBoolean(
					true);
			java.util.concurrent.atomic.AtomicBoolean paired = new java.util.concurrent.atomic.AtomicBoolean(
					true);
			f.entry.addIndexDiffChangedListener((repository, data) -> {
				if (data != f.entry.getIndexDiff()) {
					paired.set(false);
				}
				published.add(data);
				if (again.compareAndSet(true, false)) {
					f.entry.refresh();
				}
			});
			f.change();
			f.entry.refresh();
			waitForJobs();
			assertTrue(paired.get());
			org.junit.Assert.assertEquals(2, published.size());
			assertSame(published.get(0), published.get(1));
			assertSame(published.get(1), f.entry.getIndexDiff());
			assertTrue(f.entry.getIndexDiff().getUntracked()
					.contains("monitor-new"));
		}
	}

	@Test
	public void disposingWhileHookRunsCancelsWithoutPublishingOrResurrection()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(
				gateHook() + inclusiveHook())) {
			java.util.List<IndexDiffData> published = new java.util.concurrent.CopyOnWriteArrayList<>();
			f.entry.addIndexDiffChangedListener(
					(repository, data) -> published.add(data));
			Path metadata = f.repository.getDirectory().toPath();
			f.change();
			Files.writeString(metadata.resolve("gate-enabled"), "gate");
			f.entry.refresh();
			java.util.concurrent.ExecutorService disposer = java.util.concurrent.Executors
					.newSingleThreadExecutor();
			try {
				awaitReady(metadata);
				java.util.concurrent.Future<?> disposed = disposer
						.submit(() -> f.entry.dispose());
				Field flag = IndexDiffCacheEntry.class
						.getDeclaredField("disposed");
				flag.setAccessible(true);
				long deadline = System.nanoTime() + 3_000_000_000L;
				while (!flag.getBoolean(f.entry)
						&& System.nanoTime() < deadline) {
					Thread.sleep(10);
				}
				assertTrue(flag.getBoolean(f.entry));
				Files.writeString(metadata.resolve("hook-release"), "release");
				disposed.get(10, java.util.concurrent.TimeUnit.SECONDS);
				waitForJobs();
				org.junit.Assert.assertNull(f.entry.getIndexDiff());
				assertTrue(published.isEmpty());
			} finally {
				Files.writeString(metadata.resolve("hook-release"), "release");
				disposer.shutdownNow();
				assertTrue(disposer.awaitTermination(10,
						java.util.concurrent.TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void independentCacheEntriesNeverShareResultOrTokenOwnership()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(inclusiveHook())) {
			IndexDiffCacheEntry other = new IndexDiffCacheEntry(f.repository,
					null);
			try {
				waitForJobs();
				assertNotSame(f.entry.getIndexDiff(), other.getIndexDiff());
				equalData(f.entry.getIndexDiff(), other.getIndexDiff());
				f.change();
				f.entry.refresh();
				other.refresh();
				waitForJobs();
				IndexDiffData first = f.entry.getIndexDiff();
				IndexDiffData second = other.getIndexDiff();
				assertNotSame(first, second);
				equalData(first, second);
				f.entry.refresh();
				other.refresh();
				waitForJobs();
				// A peer publication may invalidate correlation after its
				// incremental event was applied. Finish that revalidation first.
				first = f.entry.getIndexDiff();
				second = other.getIndexDiff();
				assertNotSame(first, second);
				equalData(first, second);
				f.entry.refresh();
				other.refresh();
				waitForJobs();
				assertSame(first, f.entry.getIndexDiff());
				assertSame(second, other.getIndexDiff());
			} finally {
				other.dispose();
				waitForJobs();
			}
		}
	}

	@Test
	public void connectedWorkspaceContentEventRevalidatesRestoredTimestamp()
			throws Exception {
		try (MonitoredFixture f = new MonitoredFixture(
				trackedHook().replace("tracked", "project/tracked"));
				org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(
						f.repository)) {
			org.eclipse.core.resources.IWorkspace workspace = org.eclipse.core.resources.ResourcesPlugin
					.getWorkspace();
			org.eclipse.core.resources.IProject connected = workspace.getRoot()
					.getProject("FsMonitorConnectedProject");
			try {
				org.eclipse.core.resources.IProjectDescription description = workspace
						.newProjectDescription(connected.getName());
				description.setLocation(new org.eclipse.core.runtime.Path(
						f.repository.getWorkTree().getAbsolutePath()
								+ "/project"));
				connected.create(description, null);
				connected.open(null);
				f.fixture.connect(connected);
				org.eclipse.core.resources.IFile file = connected
						.getFile("tracked");
				file.create(
						new java.io.ByteArrayInputStream("one".getBytes(
								java.nio.charset.StandardCharsets.UTF_8)),
						true, null);
				Path tracked = file.getLocation().toFile().toPath();
				java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
						.fromMillis(System.currentTimeMillis() - 60_000);
				Files.setLastModifiedTime(tracked, time);
				git.add().addFilepattern(".").call();
				git.commit()
						.setAuthor("Monitor Test", "monitor@example.invalid")
						.setCommitter("Monitor Test", "monitor@example.invalid")
						.setMessage("base").call();
				waitForJobs();
				f.entry.refresh();
				waitForJobs();
				assertTrue(f.entry.getIndexDiff().getModified().isEmpty());
				workspace.run(monitor -> {
					file.setContents(
							new java.io.ByteArrayInputStream("two".getBytes(
									java.nio.charset.StandardCharsets.UTF_8)),
							true, false, monitor);
					try {
						Files.setLastModifiedTime(tracked, time);
						Files.writeString(f.repository.getDirectory().toPath()
								.resolve("monitor-changed"), "changed");
					} catch (java.io.IOException e) {
						throw new org.eclipse.core.runtime.CoreException(
								new org.eclipse.core.runtime.Status(
										org.eclipse.core.runtime.IStatus.ERROR,
										"org.eclipse.egit.core.test",
										e.getMessage(), e));
					}
				}, null);
				waitForJobs();
				assertTrue(f.entry.getIndexDiff().getModified()
						.contains("project/tracked"));
			} finally {
				if (connected.exists()) {
					connected.delete(true, true, null);
					waitForJobs();
				}
			}
		}
	}

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}
}
