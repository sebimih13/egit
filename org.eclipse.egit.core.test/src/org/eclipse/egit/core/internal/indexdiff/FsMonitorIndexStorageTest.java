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
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.op.UntrackOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.junit.Test;

/**
 * Native FSMN storage through actual workspace staging/untracking operations.
 */
public class FsMonitorIndexStorageTest extends GitTestCase {
	@Test
	public void stagingNewFilePreservesNativeMonitorCorrelation()
			throws Exception {
		checkOperation("new");
	}

	@Test
	public void restagingChangedFilePreservesNativeMonitorCorrelation()
			throws Exception {
		checkOperation("restage");
	}

	@Test
	public void untrackingRemapsNativeDirtyPositions() throws Exception {
		checkOperation("remove");
	}

	private static void waitForJobs() throws Exception {
		org.eclipse.egit.core.test.TestUtils.waitForJobs(10000,
				org.eclipse.egit.core.JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}

	private void checkOperation(String operation) throws Exception {
		TestRepository fixture = new TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		IndexDiffCacheEntry entry = null;
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeFsMonitorIndexSupport.prepare(repository);
			String path = operation.equals("new") ? "b" : "a";
			if (!operation.equals("remove")) {
				Files.writeString(
						repository.getWorkTree().toPath().resolve(path),
						"verified content\n");
			}
			if (!operation.equals("remove"))
				NativeFsMonitorIndexSupport.report(repository, path);
			project.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
			var file = project.getProject().getFile(path);
			var expected = operation.equals("remove")
					? NativeFsMonitorIndexSupport.mutate(repository,
							"update-index", "--force-remove", path)
					: NativeFsMonitorIndexSupport.mutate(repository, "add",
							"--", path);
			if (operation.equals("remove")) {
				new UntrackOperation(List.of(file)).execute(null);
			} else {
				new AddToIndexOperation(new IResource[] { file }).execute(null);
			}
			byte[] index = Files
					.readAllBytes(repository.getIndexFile().toPath());
			assertNotNull(repository.readDirCache().getFsMonitorCache());
			assertArrayEquals(expected.payload(),
					NativeFsMonitorIndexSupport.extension(index));
			NativeFsMonitorWorkloadSupport.assertStagedMatches(repository,
					expected.index());
			assertTrue("The working resource stays present", file.exists());
			entry = new IndexDiffCacheEntry(repository, null);
			waitForJobs();
			for (int i = 0; i < 2; i++) {
				entry.refresh();
				waitForJobs();
				assertArrayEquals("Refresh is read-only", index,
						Files.readAllBytes(repository.getIndexFile().toPath()));
			}
		} finally {
			if (entry != null)
				entry.dispose();
			waitForJobs();
			fixture.dispose();
		}
	}
}
