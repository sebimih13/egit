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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.op.UntrackOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.junit.Test;

/** Advance persisted clocks through actual staging and untracking operations. */
public class FsMonitorWriterRefreshOperationTest extends GitTestCase {
	@Test
	public void incrementalRefreshQueriesMonitorWithWholeResultCacheEnabled() throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repository);
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
			repository.getConfig().save();
			byte[] original = Files.readAllBytes(repository.getIndexFile().toPath());
			cache = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			assertTrue(cache.getIndexDiff().getModified().isEmpty());
			Path a = repository.getWorkTree().toPath().resolve("a");
			var time = Files.getLastModifiedTime(a);
			Files.writeString(a, "next\n");
			Files.setLastModifiedTime(a, time);
			NativeFsMonitorIndexSupport.report(repository, "a");
			cache.refreshFiles(Set.of("a"));
			waitForJobs();
			assertEquals(Set.of("a"), cache.getIndexDiff().getModified());
			compareStatus(repository, cache.getIndexDiff(), false);
			assertArrayEquals(original, Files.readAllBytes(repository.getIndexFile().toPath()));
		} finally {
			if (cache != null) cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	@Test
	public void firstRefreshWithMissingHookStillChecksContents() throws Exception {
		checkInitiallyUnavailableHook(true);
	}

	@Test
	public void firstRefreshWithNonExecutableHookStillChecksContents() throws Exception {
		checkInitiallyUnavailableHook(false);
	}

	private void checkInitiallyUnavailableHook(boolean missing) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repository);
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
			repository.getConfig().save();
			Path executable = Path.of(repository.getConfig().getString("core", null, "fsmonitor"));
			if (missing) Files.delete(executable);
			else assertTrue(executable.toFile().setExecutable(false, false));
			Path a = repository.getWorkTree().toPath().resolve("a");
			var time = Files.getLastModifiedTime(a);
			Files.writeString(a, "next\n");
			Files.setLastModifiedTime(a, time);
			byte[] original = Files.readAllBytes(repository.getIndexFile().toPath());
			cache = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			assertEquals(Set.of("a"), cache.getIndexDiff().getModified());
			cache.refresh();
			waitForJobs();
			assertEquals(Set.of("a"), cache.getIndexDiff().getModified());
			compareStatus(repository, cache.getIndexDiff(), false);
			assertArrayEquals(original, Files.readAllBytes(repository.getIndexFile().toPath()));
		} finally {
			if (cache != null) cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	@Test
	public void externalIndexWriteDuringOwnPublicationGetsACompleteFollowup() throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repository);
			repository.getConfig().setBoolean("core", null, "untrackedCache", true);
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
			repository.getConfig().setString("status", null, "showUntrackedFiles", "all");
			repository.getConfig().save();
			String blob = repository.readDirCache().getEntry("a").getObjectId().name();
			Path external = NativeFsMonitorIndexSupport.mutate(repository, "-c", "core.fsmonitor=false",
					"update-index", "--add", "--cacheinfo", "100644," + blob + ",ghost").index();
			var replaced = new java.util.concurrent.atomic.AtomicBoolean();
			var listener = repository.getListenerList().addIndexChangedListener(event -> {
				if (replaced.compareAndSet(false, true)) {
					try {
						Files.copy(external, repository.getIndexFile().toPath(),
								java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					} catch (java.io.IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				}
			});
			try {
				cache = new IndexDiffCacheEntry(repository, null, false, true);
				waitForJobs();
				assertTrue(replaced.get());
				assertEquals(Set.of("ghost"), cache.getIndexDiff().getAdded());
				assertEquals(Set.of("ghost"), cache.getIndexDiff().getMissing());
				compareStatus(repository, cache.getIndexDiff(), false);
				var complete = cache.getIndexDiff();
				cache.refresh();
				waitForJobs();
				org.junit.Assert.assertSame(complete, cache.getIndexDiff());
			} finally {
				listener.remove();
			}
		} finally {
			if (cache != null) cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	@Test
	public void earlierSameStatEditSurvivesLaterFullRefresh() throws Exception {
		checkRetainedDirtyPaths(true);
	}

	@Test
	public void earlierSameStatEditSurvivesWithoutUntrackedCache() throws Exception {
		checkRetainedDirtyPaths(false);
	}

	private void checkRetainedDirtyPaths(boolean untrackedCache) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repository);
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
			repository.getConfig().setBoolean("core", null, "untrackedCache", untrackedCache);
			repository.getConfig().save();
			byte[] original = Files.readAllBytes(repository.getIndexFile().toPath());
			java.util.concurrent.TimeUnit.MILLISECONDS.sleep(1010 - System.currentTimeMillis() % 1000);
			Path a = repository.getWorkTree().toPath().resolve("a");
			var time = Files.getLastModifiedTime(a);
			Files.writeString(a, "next\n");
			Files.setLastModifiedTime(a, time);
			NativeFsMonitorIndexSupport.reportToken(repository, "first-clock", "a");
			cache = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			assertEquals(Set.of("a"), cache.getIndexDiff().getModified());
			compareStatus(repository, cache.getIndexDiff(), false);
			Path z = repository.getWorkTree().toPath().resolve("z");
			var zTime = Files.getLastModifiedTime(z);
			Files.writeString(z, "next\n");
			Files.setLastModifiedTime(z, zTime);
			NativeFsMonitorIndexSupport.reportToken(repository, "second-clock", "z");
			cache.refresh();
			waitForJobs();
			var second = cache.getIndexDiff();
			assertEquals(Set.of("a", "z"), second.getModified());
			compareStatus(repository, second, false);
			NativeFsMonitorIndexSupport.reportToken(repository, "quiet-clock");
			cache.refresh();
			waitForJobs();
			assertEquals(Set.of("a", "z"), cache.getIndexDiff().getModified());
			if (untrackedCache) org.junit.Assert.assertSame(second, cache.getIndexDiff());
			Files.writeString(a, "base\n");
			Files.setLastModifiedTime(a, time);
			NativeFsMonitorIndexSupport.reportToken(repository, "restored-clock", "a");
			cache.refresh();
			waitForJobs();
			assertEquals(Set.of("z"), cache.getIndexDiff().getModified());
			compareStatus(repository, cache.getIndexDiff(), false);
			assertArrayEquals(original, Files.readAllBytes(repository.getIndexFile().toPath()));
		} finally {
			if (cache != null) cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	@Test
	public void stagingAndUntrackingRetainEarlierReportedChanges() throws Exception {
		checkOperations("paths");
	}

	@Test
	public void rootReplyInvalidatesBeforeUntracking() throws Exception {
		checkOperations("root");
	}

	@Test
	public void failedReplyInvalidatesBeforeUntracking() throws Exception {
		checkOperations("failure");
	}

	@Test
	public void externalWriterCannotLoseUntrackedNotifications() throws Exception {
		checkOperations("external");
	}

	@Test
	public void identicalIndexWriteStillRefreshesConsumedPaths() throws Exception {
		checkOperations("identical");
	}

	@Test
	public void staleEventCannotNarrowNewerIndexRefresh() throws Exception {
		checkOperations("stale");
	}

	@Test
	public void monitoredCachePublicationCannotLoseLateUntrackedNotifications() throws Exception {
		checkLatePublication(false);
	}

	@Test
	public void resultCachePublicationCannotLoseLateUntrackedNotifications() throws Exception {
		checkLatePublication(true);
	}

	private void checkLatePublication(boolean resultCache) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repository);
			repository.getConfig().setBoolean("core", null, "untrackedCache", true);
			repository.getConfig().setString("status", null, "showUntrackedFiles", "all");
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache", resultCache);
			repository.getConfig().save();
			var index = repository.lockDirCache();
			try {
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			cache = new IndexDiffCacheEntry(repository, null, false, true);
			waitForJobs();
			assertTrue(repository.readDirCache().getUntrackedCache().root.valid);
			Path stamp = repository.getDirectory().toPath().resolve("publication-stamp");
			Files.writeString(stamp, "");
			Files.setLastModifiedTime(stamp,
					Files.getLastModifiedTime(repository.getWorkTree().toPath().resolve("a")));
			Path hook = Path.of(repository.getConfig().getString("core", null, "fsmonitor"));
			Files.writeString(hook, "#!/bin/sh\n"
					+ "if test -e .git/index.lock && ! test -e .git/publication-done; then\n"
					+ "  printf 'next\\n' > a\n  touch -r .git/publication-stamp a\n"
					+ "  printf 'late\\n' > late\n  touch .git/publication-done\n"
					+ "  printf 'writer-clock\\000a\\000late\\000'\n"
					+ "else\n  cat .git/fsmonitor-index.response\nfi\n");
			NativeFsMonitorIndexSupport.reportToken(repository, "after-clock");
			cache.refresh();
			waitForJobs();
			assertTrue(Files.exists(repository.getDirectory().toPath().resolve("publication-done")));
			assertTrue(cache.getIndexDiff().getUntracked().contains("late"));
			assertEquals(Set.of("a"), cache.getIndexDiff().getModified());
			compareStatus(repository, cache.getIndexDiff());
		} finally {
			if (cache != null) cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	private void checkOperations(String mode) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		CountingEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repository);
			Files.createDirectories(repository.getDirectory().toPath().resolve("info"));
			Files.writeString(repository.getDirectory().toPath().resolve("info/exclude"),
					"hidden\n");
			project.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
			cache = new CountingEntry(repository);
			waitForJobs();
			assertTrue(cache.getIndexDiff().getModified().isEmpty());
			cache.fullRefreshes = 0;
			// Do not refresh this resource: only the provider knows it exists.
			Files.writeString(repository.getWorkTree().toPath().resolve("outside"), "new\n");
			Files.writeString(repository.getWorkTree().toPath().resolve("hidden"), "ignored\n");
			if (mode.equals("identical")) {
				NativeFsMonitorIndexSupport.report(repository, "outside", "hidden");
				byte[] before = Files.readAllBytes(repository.getIndexFile().toPath());
				var index = repository.lockDirCache();
				try {
					index.write();
					assertTrue(index.commit());
				} finally {
					index.unlock();
				}
				waitForJobs();
				assertArrayEquals(before, Files.readAllBytes(repository.getIndexFile().toPath()));
				compareStatus(repository, cache.getIndexDiff());
				assertEquals(0, cache.fullRefreshes);
				return;
			}
			if (mode.equals("external") || mode.equals("stale")) {
				byte[] oldChecksum = repository.readDirCache().getReadIndexChecksum();
				NativeFsMonitorIndexSupport.reportToken(repository, "external-clock",
						"outside", "hidden");
				var written = NativeFsMonitorIndexSupport.mutate(repository,
						"update-index", "--force-write-index");
				Files.copy(written.index(), repository.getIndexFile().toPath(),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				NativeFsMonitorIndexSupport.reportToken(repository, "after-external-clock");
				repository.fireEvent(mode.equals("stale")
						? new org.eclipse.jgit.events.IndexChangedEvent(true,
								Set.of(), oldChecksum)
						: new org.eclipse.jgit.events.IndexChangedEvent(false));
				waitForJobs();
				compareStatus(repository, cache.getIndexDiff());
				assertTrue("External scope is unknown", cache.fullRefreshes > 0);
				return;
			}
			if (mode.equals("paths")) {
				Path file = repository.getWorkTree().toPath().resolve("a");
				var time = Files.getLastModifiedTime(file);
				Files.writeString(file, "next\n");
				Files.setLastModifiedTime(file, time);
				Files.writeString(repository.getWorkTree().toPath().resolve("new"), "base\n");
				var added = project.getProject().getFile("new");
				added.refreshLocal(IResource.DEPTH_ZERO, null);
				NativeFsMonitorIndexSupport.reportToken(repository, "first-clock",
						"a", "outside", "hidden");
				var expected = NativeFsMonitorIndexSupport.mutate(repository, "add", "new");
				new AddToIndexOperation(new IResource[] { added }).execute(null);
				waitForJobs();
				compare(repository, expected);
				assertEquals("The staged-path update must include persisted dirty paths",
						Set.of("a"), cache.getIndexDiff().getModified());
				assertTrue("Consumed untracked notifications must reach the retained cache",
						cache.getIndexDiff().getUntracked().contains("outside"));
				assertTrue("Consumed ignored notifications must reach the retained cache",
						cache.getIndexDiff().getIgnoredNotInIndex().contains("hidden"));
				assertEquals("Known notifications must remain incremental: " + cache.fullTrigger, 0,
						cache.fullRefreshes);
				Path earlierDirty = repository.getWorkTree().toPath().resolve("m");
				var earlierTime = Files.getLastModifiedTime(earlierDirty);
				Files.writeString(earlierDirty, "next\n");
				Files.setLastModifiedTime(earlierDirty, earlierTime);
				NativeFsMonitorIndexSupport.reportToken(repository, "second-clock");
			} else if (mode.equals("root")) {
				NativeFsMonitorIndexSupport.reportToken(repository, "root-clock", "/");
			} else {
				Files.writeString(Path.of(repository.getConfig().getString("core", null,
						"fsmonitor")), "#!/bin/sh\nexit 1\n");
			}
			var expected = NativeFsMonitorIndexSupport.mutate(repository,
					"update-index", "--force-remove", "z");
			new UntrackOperation(List.of(project.getProject().getFile("z"))).execute(null);
			waitForJobs();
			compare(repository, expected);
			if (mode.equals("paths")) {
				cache.refreshFiles(Set.of("z"));
				waitForJobs();
				assertEquals(Set.of("a", "m"), cache.getIndexDiff().getModified());
			}
			compareStatus(repository, cache.getIndexDiff());
		} finally {
			if (cache != null) cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	private static class CountingEntry extends IndexDiffCacheEntry {
		int fullRefreshes;
		String fullTrigger;

		CountingEntry(org.eclipse.jgit.lib.Repository repository) {
			super(repository, null);
		}

		@Override
		protected void scheduleReloadJob(String trigger) {
			fullRefreshes++;
			fullTrigger = trigger;
			super.scheduleReloadJob(trigger);
		}
	}

	private static void compare(org.eclipse.jgit.lib.Repository repository,
			NativeFsMonitorIndexSupport.Expected expected) throws Exception {
		assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport.extension(
				Files.readAllBytes(repository.getIndexFile().toPath())));
		NativeFsMonitorWorkloadSupport.assertStagedMatches(repository, expected.index());
	}

	private static void waitForJobs() throws Exception {
		org.eclipse.egit.core.test.TestUtils.waitForJobs(10000,
				org.eclipse.egit.core.JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}

	private static void compareStatus(org.eclipse.jgit.lib.Repository repository,
			IndexDiffData actual) throws Exception {
		compareStatus(repository, actual, true);
	}

	private static void compareStatus(org.eclipse.jgit.lib.Repository repository,
			IndexDiffData actual, boolean monitored) throws Exception {
		Set<String> added = new HashSet<>(), changed = new HashSet<>(),
				removed = new HashSet<>(), modified = new HashSet<>(),
				missing = new HashSet<>(), untracked = new HashSet<>(),
				ignored = new HashSet<>();
		for (String record : new String(NativeFsMonitorIndexSupport.status(
				repository, monitored), StandardCharsets.UTF_8).split("\0")) {
			if (record.isEmpty()) continue;
			String path = record.substring(3);
			switch (record.substring(0, 2)) {
			case "A ": added.add(path); break;
			case "AD": added.add(path); missing.add(path); break;
			case "M ": changed.add(path); break;
			case "D ": removed.add(path); break;
			case " M": modified.add(path); break;
			case " D": missing.add(path); break;
			case "??": untracked.add(path); break;
			case "!!": ignored.add(path.endsWith("/")
					? path.substring(0, path.length() - 1) : path); break;
			default: throw new AssertionError(record);
			}
		}
		assertEquals(added, actual.getAdded());
		assertEquals(changed, actual.getChanged());
		assertEquals(removed, actual.getRemoved());
		assertEquals(modified, actual.getModified());
		assertEquals(missing, actual.getMissing());
		assertEquals(untracked, actual.getUntracked());
		assertEquals(ignored, actual.getIgnoredNotInIndex());
		assertTrue(actual.getConflicting().isEmpty());
	}
}
