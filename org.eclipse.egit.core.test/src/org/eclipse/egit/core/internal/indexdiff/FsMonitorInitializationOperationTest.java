/*******************************************************************************
 * Copyright (C) 2026, and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertArrayEquals;
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
