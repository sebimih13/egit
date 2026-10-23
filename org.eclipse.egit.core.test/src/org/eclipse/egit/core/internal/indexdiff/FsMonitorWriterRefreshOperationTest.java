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

	private void checkOperations(String mode) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry cache = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repository);
			project.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
			cache = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			assertTrue(cache.getIndexDiff().getModified().isEmpty());
			if (mode.equals("paths")) {
				Path file = repository.getWorkTree().toPath().resolve("a");
				var time = Files.getLastModifiedTime(file);
				Files.writeString(file, "next\n");
				Files.setLastModifiedTime(file, time);
				Files.writeString(repository.getWorkTree().toPath().resolve("new"), "base\n");
				var added = project.getProject().getFile("new");
				added.refreshLocal(IResource.DEPTH_ZERO, null);
				NativeFsMonitorIndexSupport.reportToken(repository, "first-clock", "a");
				var expected = NativeFsMonitorIndexSupport.mutate(repository, "add", "new");
				new AddToIndexOperation(new IResource[] { added }).execute(null);
				waitForJobs();
				compare(repository, expected);
				assertEquals("The staged-path update must include persisted dirty paths",
						Set.of("a"), cache.getIndexDiff().getModified());
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
		Set<String> added = new HashSet<>(), changed = new HashSet<>(),
				removed = new HashSet<>(), modified = new HashSet<>(),
				missing = new HashSet<>(), untracked = new HashSet<>(),
				ignored = new HashSet<>();
		for (String record : new String(NativeFsMonitorIndexSupport.status(
				repository, true), StandardCharsets.UTF_8).split("\0")) {
			if (record.isEmpty()) continue;
			String path = record.substring(3);
			switch (record.substring(0, 2)) {
			case "A ": added.add(path); break;
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
