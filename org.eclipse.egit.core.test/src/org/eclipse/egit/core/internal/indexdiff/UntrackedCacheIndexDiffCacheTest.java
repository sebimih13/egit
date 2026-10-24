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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.eclipse.jgit.junit.NativeUntrackedCacheSnapshots;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;

/**
 * Index interoperability tests; they do not assert directory scan avoidance.
 */
public class UntrackedCacheIndexDiffCacheTest extends GitTestCase {
	@Test
	public void ignoredUntrackOperationRetainsOnlyStagedDeletion() throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		Path directory = Files.createTempDirectory("egit-ignored-untrack-");
		TestRepository fixture = new TestRepository(directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		try {
			Repository repository = fixture.getRepository();
			org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.prepare(repository, "all", "sub/tracked");
			Files.writeString(directory.resolve("sub/.gitignore"), "tracked\n");
			entry = new IndexDiffCacheEntry(repository, null, false);
			waitForJobs();
			new org.eclipse.egit.core.op.UntrackOperation(repository,
					List.of(new org.eclipse.core.runtime.Path(
							directory.resolve("sub/tracked").toString()))).execute(null);
			assertTrue(repository.readDirCache().getEntry("sub/tracked") == null);
			waitForJobs();
			entry.refresh();
			waitForJobs();
			IndexDiffData data = entry.getIndexDiff();
			assertEquals(Set.of("sub/tracked"), data.getRemoved());
			assertTrue(!data.getUntracked().contains("sub/tracked"));
			assertTrue(data.getAdded().isEmpty());
			assertTrue(data.getChanged().isEmpty());
			assertTrue(data.getModified().isEmpty());
			assertTrue(data.getMissing().isEmpty());
			assertTrue(data.getConflicting().isEmpty());
			String nativeStatus = new String(nativeGit(repository, "--no-optional-locks",
					"status", "--porcelain=v1", "-z", "--untracked-files=all", "--no-renames"),
					StandardCharsets.UTF_8);
			assertTrue(nativeStatus.contains("D  sub/tracked\0"));
			assertTrue(!nativeStatus.contains("?? sub/tracked\0"));
		} finally {
			if (entry != null) entry.dispose();
			waitForJobs();
			fixture.dispose();
			FileUtils.delete(directory.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	@Test
	public void completeRefreshPublishesJavaUntrackedCache() throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		Path directory = Files.createTempDirectory("egit-java-untracked-");
		TestRepository fixture = new TestRepository(directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		try {
			Repository repository = fixture.getRepository();
			var config = repository.getConfig();
			config.setBoolean("core", null, "untrackedCache", true);
			config.setBoolean("core", null, "fsmonitor", false);
			config.setString("status", null, "showUntrackedFiles", "all");
			Path excludes = repository.getDirectory().toPath().resolve("test-excludes");
			Files.writeString(excludes, "");
			config.setString("core", null, "excludesFile", excludes.toString());
			config.save();
			Files.createDirectories(directory.resolve("sub"));
			Files.writeString(directory.resolve("sub/tracked"), "base\n");
			try (Git git = Git.wrap(repository)) {
				git.add().addFilepattern("sub/tracked").call();
				git.commit().setMessage("base").call();
			}
			Files.writeString(directory.resolve("sub/loose"), "new\n");
			Files.writeString(directory.resolve("sub/.gitignore"), "*.tmp\n");
			Files.writeString(directory.resolve("sub/hidden.tmp"), "ignored\n");
			for (Path path : List.of(directory, directory.resolve("sub"))) {
				Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime
						.fromMillis(System.currentTimeMillis() - 60000));
			}
			assertTrue(repository.readDirCache().getUntrackedCache().root == null);
			entry = new IndexDiffCacheEntry(repository, null, false, true);
			waitForJobs();
			assertNonIgnoredStatus(repository, entry.getIndexDiff());
			assertTrue(repository.readDirCache().getUntrackedCache().root.valid);
			Files.writeString(directory.resolve("sub/new"), "next\n");
			entry.refresh();
			waitForJobs();
			assertNonIgnoredStatus(repository, entry.getIndexDiff());
			assertTrue(entry.getIndexDiff().getUntracked().contains("sub/new"));
			byte[] published = Files.readAllBytes(repository.getIndexFile().toPath());
			nativeGit(repository, "--no-optional-locks", "status", "--porcelain=v1",
					"-z", "--untracked-files=all", "--no-renames");
			assertArrayEquals(published, Files.readAllBytes(repository.getIndexFile().toPath()));
		} finally {
			if (entry != null) entry.dispose();
			waitForJobs();
			fixture.dispose();
			FileUtils.delete(directory.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	@Test
	public void nativeAllCacheSupportsRetainedNonIgnoredStatus() throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		Path directory = Files.createTempDirectory("egit-native-untracked-");
		TestRepository fixture = new TestRepository(directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		try {
			Repository repository = fixture.getRepository();
			Files.createDirectories(directory.resolve("sub"));
			Files.writeString(directory.resolve("sub/.gitignore"), "*.tmp\n");
			Files.writeString(directory.resolve("sub/hidden.tmp"), "ignored\n");
			Path excludes = repository.getDirectory().toPath().resolve("test-excludes");
			Files.writeString(excludes, "");
			repository.getConfig().setString("core", null, "excludesFile", excludes.toString());
			repository.getConfig().save();
			org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.prepare(repository, "all", "sub/tracked");
			byte[] original = Files.readAllBytes(repository.getIndexFile().toPath());
			entry = new IndexDiffCacheEntry(repository, null, false);
			waitForJobs();
			assertNonIgnoredStatus(repository, entry.getIndexDiff());
			Files.writeString(directory.resolve("sub/.gitignore"), "\n");
			entry.refresh();
			waitForJobs();
			assertNonIgnoredStatus(repository, entry.getIndexDiff());
			assertTrue(entry.getIndexDiff().getUntracked().contains("sub/hidden.tmp"));
			Files.writeString(directory.resolve("sub/added"), "new\n");
			entry.refreshFiles(Set.of("sub/added"));
			waitForJobs();
			assertNonIgnoredStatus(repository, entry.getIndexDiff());
			assertArrayEquals(original, Files.readAllBytes(repository.getIndexFile().toPath()));
		} finally {
			if (entry != null) entry.dispose();
			waitForJobs();
			fixture.dispose();
			FileUtils.delete(directory.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	private void assertNonIgnoredStatus(Repository repository, IndexDiffData actual)
			throws Exception {
		Set<String> expected = new java.util.HashSet<>();
		for (String record : new String(nativeGit(repository, "--no-optional-locks",
				"status", "--porcelain=v1", "-z", "--untracked-files=all", "--no-renames"),
				StandardCharsets.UTF_8).split("\0")) {
			if (record.startsWith("?? ")) expected.add(record.substring(3));
			else if (!record.isEmpty()) throw new AssertionError(record);
		}
		assertEquals(expected, actual.getUntracked());
		assertTrue(actual.getIgnoredNotInIndex().isEmpty());
		assertTrue(actual.getAdded().isEmpty());
		assertTrue(actual.getChanged().isEmpty());
		assertTrue(actual.getRemoved().isEmpty());
		assertTrue(actual.getModified().isEmpty());
		assertTrue(actual.getMissing().isEmpty());
		assertTrue(actual.getConflicting().isEmpty());
	}

	@Test
	public void nativeDirectoryListingReusePreservesIgnoredRefreshResults()
			throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		for (String mode : new String[] { "normal", "all" }) {
			Path directory = Files.createTempDirectory("egit-untracked-listing-");
			TestRepository fixture = new TestRepository(directory.resolve(".git").toFile());
			Repository repository = fixture.getRepository();
			IndexDiffCacheEntry entry = null;
			try {
				Files.createDirectories(directory.resolve("sub"));
				Files.writeString(directory.resolve("sub/.gitignore"), "*.tmp\n");
				Files.writeString(directory.resolve("sub/hidden.tmp"), "ignored\n");
				org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.prepare(repository, mode, "sub/tracked");
				byte[] index = Files.readAllBytes(repository.getIndexFile().toPath());
				entry = new IndexDiffCacheEntry(repository, null);
				waitForJobs();
				for (int i = 0; i < 3; i++) {
					entry.refresh();
					waitForJobs();
					assertListingStatus(repository, entry.getIndexDiff());
					assertEquals(Set.of("sub/hidden.tmp"), entry.getIndexDiff().getIgnoredNotInIndex());
				}
				Files.writeString(directory.resolve("sub/tracked"), "longer content\n");
				Files.writeString(directory.resolve("sub/.gitignore"), "\n");
				entry.refresh();
				waitForJobs();
				assertListingStatus(repository, entry.getIndexDiff());
				assertTrue(entry.getIndexDiff().getIgnoredNotInIndex().isEmpty());
				Files.writeString(directory.resolve("sub/new"), "new\n");
				Files.delete(directory.resolve("sub/loose"));
				entry.refresh();
				waitForJobs();
				assertListingStatus(repository, entry.getIndexDiff());
				assertArrayEquals("Refresh cannot write the native index", index,
						Files.readAllBytes(repository.getIndexFile().toPath()));
			} finally {
				if (entry != null) entry.dispose();
				waitForJobs();
				fixture.dispose();
				FileUtils.delete(directory.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
			}
		}
	}

	private void assertListingStatus(Repository repository, IndexDiffData actual)
			throws Exception {
		byte[] output = nativeGit(repository, "--no-optional-locks", "status",
				"--porcelain=v1", "-z", "--untracked-files=all", "--ignored=matching", "--no-renames");
		Set<String> untracked = new java.util.HashSet<>();
		Set<String> ignored = new java.util.HashSet<>();
		Set<String> modified = new java.util.HashSet<>();
		for (String record : new String(output, StandardCharsets.UTF_8).split("\0")) {
			if (record.startsWith("?? ")) untracked.add(record.substring(3));
			else if (record.startsWith("!! ")) ignored.add(record.substring(3));
			else if (record.startsWith(" M ")) modified.add(record.substring(3));
			else if (!record.isEmpty()) throw new AssertionError(record);
		}
		assertEquals(untracked, actual.getUntracked());
		assertEquals(ignored, actual.getIgnoredNotInIndex());
		assertEquals(modified, actual.getModified());
		assertTrue(actual.getAdded().isEmpty());
		assertTrue(actual.getChanged().isEmpty());
		assertTrue(actual.getRemoved().isEmpty());
		assertTrue(actual.getMissing().isEmpty());
		assertTrue(actual.getConflicting().isEmpty());
	}

	@Test
	public void pathCheckoutRetainsNativeIndexReplacementCache() throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		try {
			fixture.connect(project.getProject());
			Repository repository = fixture.getRepository();
			for (String mode : List.of("normal", "all")) {
				var scenario = org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.prepare(repository, mode, "two");
				try (var reader = repository.newObjectReader(); var tree = org.eclipse.jgit.treewalk.TreeWalk.forPath(
						reader, "two", scenario.target().getTree())) {
					NativeFsMonitorWorkloadSupport.nativeUntrackedIndexMutation(repository, null,
							"--cacheinfo", "100644," + tree.getObjectId(0).name() + ",two");
				}
				project.getProject().refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
				new org.eclipse.egit.core.op.DiscardChangesOperation(
						new org.eclipse.core.resources.IResource[] { project.getProject().getFile("two") },
						scenario.target().name()).execute(null);
				org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.assertResult(repository, scenario, false);
			}
		} finally {
			fixture.dispose();
		}
	}

	@Test
	public void unchangedBranchCheckoutPreservesNativeCache() throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		try {
			fixture.connect(project.getProject());
			Repository repository = fixture.getRepository();
			for (String mode : List.of("normal", "all")) {
				var scenario = org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.prepare(repository, mode, "two");
				NativeFsMonitorWorkloadSupport.nativeUntrackedReadTree(repository, scenario.base().getTree());
				new org.eclipse.egit.core.op.BranchOperation(repository, scenario.base().name()).execute(null);
				NativeFsMonitorWorkloadSupport.assertNativeUntrackedCachePolicy(repository);
				assertTrue(repository.readDirCache().getUntrackedCache().root.valid);
				assertEquals(scenario.base(), repository.resolve("HEAD"));
			}
		} finally {
			fixture.dispose();
		}
	}

	@Test
	public void rejectedBranchCheckoutPreservesIndexAndUserChanges() throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		try {
			fixture.connect(project.getProject());
			Repository repository = fixture.getRepository();
			for (String mode : List.of("normal", "all")) {
				for (String path : List.of("two", "sub/tracked")) {
					var scenario = org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.prepare(repository, mode, path);
					byte[] index = Files.readAllBytes(repository.getIndexFile().toPath());
					Files.writeString(repository.getWorkTree().toPath().resolve(path), "user\n");
					NativeFsMonitorWorkloadSupport.nativeUntrackedCheckoutRejected(repository, scenario.target());
					var operation = new org.eclipse.egit.core.op.BranchOperation(repository, scenario.target().name());
					operation.execute(null);
					assertEquals(org.eclipse.jgit.api.CheckoutResult.Status.CONFLICTS,
							operation.getResult(repository).getStatus());
					assertArrayEquals(index, Files.readAllBytes(repository.getIndexFile().toPath()));
					assertEquals(scenario.base(), repository.resolve("HEAD"));
					assertEquals("user\n", Files.readString(repository.getWorkTree().toPath().resolve(path)));
				}
			}
		} finally {
			fixture.dispose();
		}
	}

	@Test
	public void branchCheckoutMatchesNativeUnpackTreeInvalidation() throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		try {
			fixture.connect(project.getProject());
			Repository repository = fixture.getRepository();
			for (String mode : List.of("normal", "all")) {
				for (String path : List.of("two", "sub/tracked")) {
					var scenario = org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.prepare(repository, mode, path);
					NativeFsMonitorWorkloadSupport.nativeUntrackedReadTree(repository, scenario.target().getTree());
					new org.eclipse.egit.core.op.BranchOperation(repository, scenario.target().name()).execute(null);
					org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport.assertResult(repository, scenario, true);
					assertTrue("Checkout keeps the tracked Eclipse project", project.getProject().isOpen());
				}
			}
		} finally {
			fixture.dispose();
		}
	}

	@Test
	public void stagingNewPathPreservesUnchangedNativeCacheDirectories() throws Exception {
		checkSelectiveIndexMutation(23, false, 24);
	}

	@Test
	public void untrackingPreservesUnchangedNativeCacheDirectories() throws Exception {
		checkSelectiveIndexMutation(20, true, 21);
	}

	@Test
	public void restagingExistingPathPreservesNativeCachePayload() throws Exception {
		checkSelectiveIndexMutation(20, false, 20);
	}

	private void checkSelectiveIndexMutation(int checkpoint, boolean remove, int expected)
			throws Exception {
		var file = testUtils.addFileToProject(project.getProject(), "two", "");
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		try {
			fixture.connect(project.getProject());
			Repository repository = fixture.getRepository();
			repository.getConfig().setBoolean("feature", null, "manyFiles", false);
			repository.getConfig().setString("core", null, "untrackedCache", "keep");
			NativeUntrackedCacheSnapshots.install(repository, checkpoint);
			if (remove) {
				NativeFsMonitorWorkloadSupport.nativeUntrackedIndexMutation(repository,
						null, "--force-remove", "two");
				new org.eclipse.egit.core.op.UntrackOperation(List.of(file)).execute(null);
			} else {
				NativeFsMonitorWorkloadSupport.nativeUntrackedIndexMutation(repository,
						null, "--add", "--cacheinfo",
						"100644," + org.eclipse.jgit.lib.Constants.EMPTY_BLOB_ID.name() + ",two");
				new org.eclipse.egit.core.op.AddToIndexOperation(
						new org.eclipse.core.resources.IResource[] { file }).execute(null);
			}
			NativeFsMonitorWorkloadSupport.assertNativeUntrackedCachePolicy(repository);
			NativeUntrackedCacheSnapshots.assertNativeDump(
					repository.readDirCache().getUntrackedCache(), expected);
			org.junit.Assert.assertEquals(!remove,
					repository.readDirCache().getEntry("two") != null);
			assertTrue("The index operation keeps the working file", file.exists());
		} finally {
			fixture.dispose();
		}
	}

	@Test
	public void stagingAppliesNativeUntrackedCachePolicy() throws Exception {
		var file = testUtils.addFileToProject(project.getProject(), "policy", "content");
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		try {
			fixture.connect(project.getProject());
			Repository repository = fixture.getRepository();
			for (boolean manyFiles : new boolean[] { false, true }) {
				for (boolean existing : new boolean[] { false, true }) {
					for (String value : NativeFsMonitorWorkloadSupport.UNTRACKED_SETTINGS) {
						NativeFsMonitorWorkloadSupport
								.nativeUntrackedCachePolicy(repository, manyFiles, value, existing);
						repository.getConfig().setBoolean("feature", null, "manyFiles", manyFiles);
						if (value == null)
							repository.getConfig().unset("core", null, "untrackedCache");
						else
							repository.getConfig().setString("core", null, "untrackedCache", value);
						new org.eclipse.egit.core.op.AddToIndexOperation(
								new org.eclipse.core.resources.IResource[] { file }).execute(null);
						NativeFsMonitorWorkloadSupport
								.assertNativeUntrackedCachePolicy(repository);
						org.junit.Assert.assertEquals("Staged through the EGit operation",
								"policy", repository.readDirCache().getEntry(0).getPathString());
					}
				}
			}
		} finally {
			fixture.dispose();
		}
	}

	@Test
	public void nativeVersion2CacheSurvivesFullAndIncrementalRefresh()
			throws Exception {
		checkNativeCache("2", "all");
	}

	@Test
	public void nativeVersion3CacheSurvivesFullAndIncrementalRefresh()
			throws Exception {
		checkNativeCache("3", "all");
	}

	@Test
	public void nativeVersion4CacheSurvivesFullAndIncrementalRefresh()
			throws Exception {
		checkNativeCache("4", "all");
	}

	@Test
	public void nativeNormalModeVersion2SummariesSurviveRefresh() throws Exception {
		checkNativeCache("2", "normal");
	}

	@Test
	public void nativeNormalModeVersion3SummariesSurviveRefresh() throws Exception {
		checkNativeCache("3", "normal");
	}

	@Test
	public void nativeNormalModeVersion4SummariesSurviveRefresh() throws Exception {
		checkNativeCache("4", "normal");
	}

	@Test
	public void originalNativeDumpCheckpointsSurviveCacheRefresh()
			throws Exception {
		for (int checkpoint : NativeUntrackedCacheSnapshots.checkpoints()) {
			Path directory = Files.createTempDirectory("egit-t7063-snapshot-");
			TestRepository fixture = new TestRepository(
					directory.resolve(".git").toFile());
			Repository repository = fixture.getRepository();
			IndexDiffCacheEntry entry = null;
			try {
				repository.getConfig().setBoolean("core", null,
						"untrackedCache", true);
				repository.getConfig().setBoolean("jgit", null,
						"fsmonitorCache", false);
				byte[] index = NativeUntrackedCacheSnapshots.install(repository,
						checkpoint);
				Files.writeString(directory.resolve("loose"), "untracked");
				entry = new IndexDiffCacheEntry(repository, null);
				waitForJobs();
				assertEquals(Set.of("loose"), entry.getIndexDiff().getUntracked());
				Set<String> indexed = new java.util.TreeSet<>();
				var cache = repository.readDirCache();
				for (int i = 0; i < cache.getEntryCount(); i++)
					indexed.add(cache.getEntry(i).getPathString());
				assertEquals(indexed, entry.getIndexDiff().getAdded());
				NativeUntrackedCacheSnapshots.assertStored(repository, checkpoint);
				assertArrayEquals(index,
						Files.readAllBytes(repository.getIndexFile().toPath()));
				Files.move(directory.resolve("loose"), directory.resolve("renamed"));
				entry.refreshFiles(List.of("loose", "renamed"));
				waitForJobs();
				assertEquals(Set.of("renamed"), entry.getIndexDiff().getUntracked());
				entry.refresh();
				waitForJobs();
				assertEquals(Set.of("renamed"), entry.getIndexDiff().getUntracked());
				assertEquals(indexed, entry.getIndexDiff().getAdded());
				NativeUntrackedCacheSnapshots.assertStored(repository, checkpoint);
				assertArrayEquals(index,
						Files.readAllBytes(repository.getIndexFile().toPath()));
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
	}

	private void checkNativeCache(String version, String mode) throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		Path directory = Files.createTempDirectory("egit-untracked-interop-");
		TestRepository fixture = new TestRepository(
				directory.resolve(".git").toFile());
		Repository repository = fixture.getRepository();
		IndexDiffCacheEntry entry = null;
		try (Git git = new Git(repository)) {
			Files.writeString(directory.resolve("tracked"), "base");
			Files.writeString(directory.resolve(".gitignore"), "*.tmp\n");
			git.add().addFilepattern(".").call();
			git.commit().setAuthor("Cache Test", "cache@example.invalid")
					.setCommitter("Cache Test", "cache@example.invalid")
					.setMessage("base").call();
			Files.createDirectory(directory.resolve("directory"));
			Files.writeString(directory.resolve("directory/first"), "new");
			Files.writeString(directory.resolve("hidden.tmp"), "ignored");
			nativeGit(repository, "config", "core.untrackedCache", "true");
			nativeGit(repository, "config", "status.showUntrackedFiles", mode);
			nativeGit(repository, "update-index", "--untracked-cache");
			if ("3".equals(version)) {
				nativeGit(repository, "update-index", "--skip-worktree",
						"tracked");
			}
			nativeGit(repository, "update-index", "--index-version", version);
			// Native cache population must not race the directory timestamp
			// tick.
			Thread.sleep(1100);
			nativeGit(repository, "status", "--porcelain=v1", "-z",
					"--untracked-files=" + mode);
			nativeGit(repository, "status", "--porcelain=v1", "-z",
					"--untracked-files=" + mode);
			byte[] populated = repository.readDirCache().getUntrackedCache().write();
			nativeGit(repository, "status", "--porcelain=v1", "-z",
					"--untracked-files=" + ("all".equals(mode) ? "normal" : "all"));
			assertArrayEquals("Native mode bypass must preserve UNTR summaries",
					populated, repository.readDirCache().getUntrackedCache().write());
			repository.getConfig().load();
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					false);
			assertNotNull(repository.readDirCache().getUntrackedCache());
			assertEquals("all".equals(mode) ? 0 : 6,
					repository.readDirCache().getUntrackedCache().dirFlags);
			byte[] index = Files
					.readAllBytes(repository.getIndexFile().toPath());
			assertEquals(Integer.parseInt(version),
					java.nio.ByteBuffer.wrap(index).getInt(4));
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			assertPaths(entry.getIndexDiff(), "directory/first");
			for (String setting : new String[] { "false", "true", "keep" }) {
				repository.getConfig().setString("core", null, "untrackedCache",
						setting);
				entry.refresh();
				waitForJobs();
				assertPaths(entry.getIndexDiff(), "directory/first");
				assertArrayEquals(index,
						Files.readAllBytes(repository.getIndexFile().toPath()));
				assertArrayEquals(populated,
						repository.readDirCache().getUntrackedCache().write());
			}
			Files.move(directory.resolve("directory/first"),
					directory.resolve("directory/renamed"));
			entry.refreshFiles(List.of("directory/first", "directory/renamed"));
			waitForJobs();
			assertPaths(entry.getIndexDiff(), "directory/renamed");
			entry.refresh();
			waitForJobs();
			assertPaths(entry.getIndexDiff(), "directory/renamed");
			assertArrayEquals(index,
					Files.readAllBytes(repository.getIndexFile().toPath()));
			assertNotNull(repository.readDirCache().getUntrackedCache());
			assertEquals("?? directory/renamed\0",
					new String(nativeGit(repository, "--no-optional-locks",
							"-c", "core.untrackedCache=false", "status",
							"--porcelain=v1", "-z", "--untracked-files=all"),
							StandardCharsets.UTF_8));
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

	private static void assertPaths(IndexDiffData data, String untracked) {
		assertNotNull(data);
		assertEquals(Set.of(untracked), data.getUntracked());
		assertEquals(Set.of("hidden.tmp"), data.getIgnoredNotInIndex());
		assertTrue(data.getAdded().isEmpty());
		assertTrue(data.getChanged().isEmpty());
		assertTrue(data.getModified().isEmpty());
		assertTrue(data.getMissing().isEmpty());
		assertTrue(data.getRemoved().isEmpty());
		assertTrue(data.getConflicting().isEmpty());
	}

	private static byte[] nativeGit(Repository repository, String... arguments)
			throws Exception {
		List<String> command = new ArrayList<>(
				List.of(System.getProperty("jgit.test.gitExecutable", "git"),
						"-c", "core.fsmonitor=false"));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(repository.getWorkTree());
		builder.environment().keySet()
				.removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		Path output = Files.createTempFile(repository.getDirectory().toPath(),
				"native-cache-", ".out");
		builder.redirectErrorStream(true).redirectOutput(output.toFile());
		Process process = builder.start();
		try {
			assertTrue("Native Git must finish",
					process.waitFor(10, TimeUnit.SECONDS));
			assertTrue("Bound native output",
					Files.size(output) <= 1024 * 1024);
			byte[] result = Files.readAllBytes(output);
			assertEquals(new String(result, StandardCharsets.UTF_8), 0,
					process.exitValue());
			return result;
		} finally {
			process.destroyForcibly();
			Files.deleteIfExists(output);
		}
	}

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}
}
