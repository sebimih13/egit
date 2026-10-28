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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.junit.Test;

/** Native t7519 initialization through actual workspace staging. */
public class FsMonitorInitializationOperationTest extends GitTestCase {

	@Test
	public void firstStagingCreatesNativeMonitorState() throws Exception {
		checkFirstAdd(false, false);
	}

	@Test
	public void firstStagingWithMissingIndexCreatesNativeMonitorState()
			throws Exception {
		checkFirstAdd(true, false);
	}

	@Test
	public void failedInitialHookRetainsNativeDirtyState() throws Exception {
		checkFirstAdd(false, true);
	}

	@Test
	public void firstReadOnlyRefreshWithoutFSMNChecksSameStatEdits() throws Exception {
		checkFirstReadOnlyRefresh(false);
	}

	@Test
	public void failedFirstQueryDoesNotHideChangesOrCreateStorage() throws Exception {
		checkFirstReadOnlyRefresh(true);
	}

	@Test
	public void firstUnbornRefreshPublishesInitiallyAbsentIndex() throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			assertNull(repository.resolve(org.eclipse.jgit.lib.Constants.HEAD));
			assertTrue(Files.notExists(repository.getIndexFile().toPath()));
			Path hook = repository.getDirectory().toPath().resolve("hooks/bootstrap-monitor");
			Files.createDirectories(hook.getParent());
			Files.writeString(hook, "#!/bin/sh\nprintf 'initial-clock\\000'\n");
			assertTrue(hook.toFile().setExecutable(true));
			repository.getConfig().setString("core", null, "fsmonitor", hook.toString());
			repository.getConfig().setInt("core", null, "fsmonitorHookVersion", 2);
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache", false);
			repository.getConfig().setBoolean("core", null, "untrackedCache", true);
			repository.getConfig().setString("status", null, "showUntrackedFiles", "all");
			repository.getConfig().save();
			Files.writeString(repository.getWorkTree().toPath().resolve("loose"), "new\n");
			var publications = new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
			var listener = repository.getListenerList().addIndexChangedListener(event -> {
				try {
					var untracked = repository.readDirCache().getUntrackedCache();
					publications.add(untracked != null && untracked.root != null && untracked.root.valid);
				} catch (java.io.IOException failure) {
					throw new java.io.UncheckedIOException(failure);
				}
			});
			try {
				cache = new IndexDiffCacheEntry(repository, null, false, true);
				waitForJobs();
				assertTrue(!publications.isEmpty());
				assertEquals(Boolean.TRUE, publications.get(0));
			} finally {
				listener.remove();
			}
			var index = repository.readDirCache();
			assertEquals(0, index.getEntryCount());
			assertNotNull(index.getFsMonitorCache());
			assertTrue(index.getUntrackedCache().root.valid);
			var nativePaths = new java.util.HashSet<String>();
			byte[] output = NativeFsMonitorIndexSupport.status(repository, false);
			for (String record : new String(output, java.nio.charset.StandardCharsets.UTF_8).split("\0")) {
				if (record.startsWith("?? ")) nativePaths.add(record.substring(3));
				else assertTrue(record, record.isEmpty() || record.startsWith("!! "));
			}
			assertEquals(nativePaths, cache.getIndexDiff().getUntracked());
			assertTrue(cache.getIndexDiff().getAdded().isEmpty());
			assertTrue(cache.getIndexDiff().getChanged().isEmpty());
			assertTrue(cache.getIndexDiff().getRemoved().isEmpty());
			assertTrue(cache.getIndexDiff().getModified().isEmpty());
			assertTrue(cache.getIndexDiff().getMissing().isEmpty());
			assertTrue(cache.getIndexDiff().getConflicting().isEmpty());
		} finally {
			if (cache != null) cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	private void checkFirstReadOnlyRefresh(boolean failedHook) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepareWithoutExtension(repository);
			assertNull(repository.readDirCache().getFsMonitorCache());
			byte[] original = Files.readAllBytes(repository.getIndexFile().toPath());
			Path file = repository.getWorkTree().toPath().resolve("a");
			var time = Files.getLastModifiedTime(file);
			java.util.concurrent.TimeUnit.MILLISECONDS.sleep(1010 - System.currentTimeMillis() % 1000);
			Files.writeString(file, "edit\n");
			Files.setLastModifiedTime(file, time);
			if (failedHook) {
				Files.writeString(Path.of(repository.getConfig().getString("core", null, "fsmonitor")),
						"#!/bin/sh\nexit 1\n");
			}
			cache = new IndexDiffCacheEntry(repository, null, false);
			waitForJobs();
			assertEquals(java.util.Set.of("a"), cache.getIndexDiff().getModified());
			cache.refresh();
			waitForJobs();
			assertEquals(java.util.Set.of("a"), cache.getIndexDiff().getModified());
			NativeFsMonitorWorkloadSupport.assertNativeDiff(repository, false, null, java.util.Set.of("a"));
			assertArrayEquals(original, Files.readAllBytes(repository.getIndexFile().toPath()));
			assertNull(repository.readDirCache().getFsMonitorCache());
		} finally {
			if (cache != null) cache.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}

	private void checkFirstAdd(boolean missingIndex, boolean failedHook)
			throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepareWithoutExtension(repository);
			if (missingIndex) {
				Files.delete(repository.getIndexFile().toPath());
			}
			if (failedHook) {
				Files.writeString(Path.of(repository.getConfig()
						.getString("core", null, "fsmonitor")),
						"#!/bin/sh\nexit 1\n");
			}
			Files.writeString(repository.getWorkTree().toPath().resolve("new"),
					"base\n");
			project.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
			var expected = NativeFsMonitorIndexSupport.mutate(repository, "add",
					"new");
			new AddToIndexOperation(
					new IResource[] { project.getProject().getFile("new") })
					.execute(null);
			var index = repository.readDirCache();
			assertNotNull(index.getFsMonitorCache());
			assertTrue(index.isFsMonitorValid(index.findEntry("new")));
			byte[] bytes = Files
					.readAllBytes(repository.getIndexFile().toPath());
			assertArrayEquals(expected.payload(),
					NativeFsMonitorIndexSupport.extension(bytes));
			NativeFsMonitorWorkloadSupport.assertStagedMatches(repository,
					expected.index());
			cache = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			cache.refresh();
			waitForJobs();
			assertArrayEquals("Refresh must not publish an unverified clock",
					bytes,
					Files.readAllBytes(repository.getIndexFile().toPath()));
		} finally {
			if (cache != null) {
				cache.dispose();
			}
			waitForJobs();
			fixture.dispose();
		}
	}

	private static void waitForJobs() throws Exception {
		org.eclipse.egit.core.test.TestUtils.waitForJobs(10000,
				org.eclipse.egit.core.JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}
}
