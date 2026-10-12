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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;

public class FsMonitorIndexDiffCacheTest extends GitTestCase {
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

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}
}
