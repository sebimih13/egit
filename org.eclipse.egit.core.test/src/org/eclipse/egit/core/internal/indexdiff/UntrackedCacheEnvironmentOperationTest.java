/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.junit.MockSystemReader;
import org.eclipse.jgit.junit.NativeUntrackedEnvironmentSupport;
import org.eclipse.jgit.junit.NativeUntrackedNormalSupport;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class UntrackedCacheEnvironmentOperationTest extends GitTestCase {

	@Parameterized.Parameters(name = "disable={0}, enabled={1}, manyFiles={2}")
	public static List<Object[]> scenarios() {
		return NativeUntrackedEnvironmentSupport.scenarios();
	}

	private final String value;

	private final boolean enabled;

	private final boolean many;

	public UntrackedCacheEnvironmentOperationTest(String value, boolean enabled,
			boolean many) {
		this.value = value;
		this.enabled = enabled;
		this.many = many;
	}

	@Test
	public void nativeBooleanActivationAndRetainedRefreshTransitions()
			throws Exception {
		var directory = Files
				.createTempDirectory("egit-untracked-environment-");
		TestRepository fixture = new TestRepository(
				directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		MockSystemReader reader = (MockSystemReader) SystemReader.getInstance();
		String previous = reader.getenv("GIT_DISABLE_UNTRACKED_CACHE");
		try {
			var repository = fixture.getRepository();
			NativeUntrackedEnvironmentSupport.prepare(repository, many);
			NativeUntrackedEnvironmentSupport.assertNative(repository, value,
					enabled);
			reader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", value);
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			Set<String> expected = new HashSet<>(
					Set.of("three", "dtwo/two", "dthree/three"));
			refresh(entry);
			check(entry.getIndexDiff(), expected);
			refresh(entry);
			check(entry.getIndexDiff(), expected);
			NativeUntrackedEnvironmentSupport.assertPublication(repository,
					enabled, Set.of("three", "dtwo/", "dthree/"));
			reader.setProperty("GIT_DISABLE_UNTRACKED_CACHE",
					enabled ? "1" : "0");
			var parent = repository.getWorkTree().toPath().resolve("dthree");
			var time = Files.getLastModifiedTime(parent);
			NativeUntrackedNormalSupport.write(repository, "dthree/later",
					"later\n");
			Files.setLastModifiedTime(parent, time);
			expected.add("dthree/later");
			refresh(entry);
			check(entry.getIndexDiff(), expected);
			refresh(entry);
			check(entry.getIndexDiff(), expected);
			if (!enabled)
				NativeUntrackedEnvironmentSupport.assertPublication(repository,
						true, Set.of("three", "dtwo/", "dthree/"));
		} finally {
			if (entry != null)
				entry.dispose();
			waitForJobs();
			reader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", previous);
			fixture.dispose();
			FileUtils.delete(directory.toFile(),
					FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}

	@Test
	public void environmentPolicyAppliesToRetainedMonitorResults()
			throws Exception {
		var directory = Files.createTempDirectory("egit-monitored-environment-");
		TestRepository fixture = new TestRepository(directory.resolve(".git").toFile());
		IndexDiffCacheEntry entry = null;
		MockSystemReader reader = (MockSystemReader) SystemReader.getInstance();
		String previous = reader.getenv("GIT_DISABLE_UNTRACKED_CACHE");
		try {
			var repository = fixture.getRepository();
			NativeUntrackedEnvironmentSupport.prepare(repository, many);
			NativeUntrackedEnvironmentSupport.enableQuietMonitor(repository);
			reader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", "0");
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			refresh(entry);
			refresh(entry);
			IndexDiffData warm = entry.getIndexDiff();
			refresh(entry);
			assertSame(warm, entry.getIndexDiff());
			reader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", value);
			refresh(entry);
			if (enabled) {
				assertSame(warm, entry.getIndexDiff());
			} else {
				assertNotSame(warm, entry.getIndexDiff());
			}
			reader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", "1");
			NativeUntrackedNormalSupport.write(repository, "dthree/later", "later\n");
			Set<String> expected = new HashSet<>(
					Set.of("three", "dtwo/two", "dthree/three", "dthree/later"));
			refresh(entry);
			check(entry.getIndexDiff(), expected);
			assertEquals(expected, NativeUntrackedNormalSupport.nativeStatus(repository, "all", false));
			Files.delete(repository.getWorkTree().toPath().resolve("dthree/three"));
			expected.remove("dthree/three");
			refresh(entry);
			check(entry.getIndexDiff(), expected);
			assertEquals(expected, NativeUntrackedNormalSupport.nativeStatus(repository, "all", false));
		} finally {
			if (entry != null)
				entry.dispose();
			waitForJobs();
			reader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", previous);
			fixture.dispose();
			FileUtils.delete(directory.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	private static void refresh(IndexDiffCacheEntry entry) throws Exception {
		var job = entry.createRefreshResourcesAndIndexDiffJob();
		job.schedule();
		assertTrue(job.join(10000, null));
		assertTrue(job.getResult().toString(), job.getResult().isOK());
		waitForJobs();
	}

	private static void check(IndexDiffData data, Set<String> expected) {
		assertEquals(expected, data.getUntracked());
		assertTrue(data.getAdded().isEmpty());
		assertTrue(data.getChanged().isEmpty());
		assertTrue(data.getRemoved().isEmpty());
		assertTrue(data.getModified().isEmpty());
		assertTrue(data.getMissing().isEmpty());
		assertTrue(data.getIgnoredNotInIndex().isEmpty());
		assertTrue(data.getConflicting().isEmpty());
	}
}
