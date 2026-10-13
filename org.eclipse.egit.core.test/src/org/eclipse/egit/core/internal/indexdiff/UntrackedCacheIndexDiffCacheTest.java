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
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;

/**
 * Index interoperability tests; they do not assert directory scan avoidance.
 */
public class UntrackedCacheIndexDiffCacheTest extends GitTestCase {
	@Test
	public void nativeVersion2CacheSurvivesFullAndIncrementalRefresh()
			throws Exception {
		checkNativeCache("2");
	}

	@Test
	public void nativeVersion3CacheSurvivesFullAndIncrementalRefresh()
			throws Exception {
		checkNativeCache("3");
	}

	@Test
	public void nativeVersion4CacheSurvivesFullAndIncrementalRefresh()
			throws Exception {
		checkNativeCache("4");
	}

	private void checkNativeCache(String version) throws Exception {
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
					"--untracked-files=all");
			nativeGit(repository, "status", "--porcelain=v1", "-z",
					"--untracked-files=all");
			repository.getConfig().load();
			repository.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					false);
			assertNotNull(repository.readDirCache().getUntrackedCache());
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
				List.of("git", "-c", "core.fsmonitor=false"));
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
