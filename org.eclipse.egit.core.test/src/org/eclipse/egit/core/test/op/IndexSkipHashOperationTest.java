/*******************************************************************************
 * Copyright (C) 2026, and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.test.op;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCacheEntry;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** C Git t1600 skipHash cases exercised through EGit staging operations. */
@RunWith(Parameterized.class)
public class IndexSkipHashOperationTest extends GitTestCase {
	@Parameterized.Parameters(name = "index version {0}")
	public static List<Object[]> versions() {
		return List.of(new Object[] { 2 }, new Object[] { 3 },
				new Object[] { 4 });
	}

	private final int version;

	private TestRepository fixture;

	private Repository repository;

	public IndexSkipHashOperationTest(int version) {
		this.version = version;
	}

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		gitDir = new File(project.getProject().getLocationURI().getPath(),
				Constants.DOT_GIT);
		fixture = new TestRepository(gitDir);
		fixture.connect(project.getProject());
		repository = fixture.getRepository();
		repository.getConfig().setInt("index", null, "version", version);
	}

	@Override
	@After
	public void tearDown() throws Exception {
		fixture.dispose();
		super.tearDown();
	}

	private IFile stage() throws Exception {
		IFile file = testUtils.addFileToProject(project.getProject(), "a",
				"one");
		new AddToIndexOperation(new IResource[] { file }).execute(null);
		return file;
	}

	private void restage(IFile file) throws Exception {
		file.setContents(
				new ByteArrayInputStream(
						"changed".getBytes(StandardCharsets.UTF_8)),
				true, false, null);
		new AddToIndexOperation(new IResource[] { file }).execute(null);
	}

	private void footer(Repository repo, boolean zero) throws Exception {
		byte[] bytes = Files.readAllBytes(repo.getIndexFile().toPath());
		byte[] actual = Arrays.copyOfRange(bytes, bytes.length - 20,
				bytes.length);
		if (zero)
			assertArrayEquals(new byte[20], actual);
		else
			assertArrayEquals(Constants.newMessageDigest()
					.digest(Arrays.copyOf(bytes, bytes.length - 20)), actual);
	}

	@Test
	public void explicitSkipHash() throws Exception {
		repository.getConfig().setBoolean("index", null, "skipHash", true);
		stage();
		footer(repository, true);
		assertEquals(1, repository.readDirCache().getEntryCount());
	}

	@Test
	public void manyFilesDefault() throws Exception {
		repository.getConfig().setBoolean("feature", null, "manyFiles", true);
		stage();
		footer(repository, true);
	}

	@Test
	public void manyFilesDefaultsToVersionFourAndZeroFooter() throws Exception {
		repository.getConfig().unset("index", null, "version");
		repository.getConfig().setBoolean("feature", null, "manyFiles", true);
		stage();
		footer(repository, true);
		assertEquals(4, org.eclipse.jgit.util.NB.decodeInt32(
				Files.readAllBytes(repository.getIndexFile().toPath()), 4));
	}

	@Test
	public void explicitFalseOverridesManyFiles() throws Exception {
		repository.getConfig().setBoolean("feature", null, "manyFiles", true);
		repository.getConfig().setBoolean("index", null, "skipHash", false);
		stage();
		footer(repository, false);
	}

	@Test
	public void existingIndexHonorsConfigChanges() throws Exception {
		IFile file = stage();
		footer(repository, false);
		repository.getConfig().setBoolean("index", null, "skipHash", true);
		restage(file);
		footer(repository, true);
		repository.getConfig().setBoolean("index", null, "skipHash", false);
		restage(file);
		footer(repository, false);
	}

	@Test
	public void existingIndexIgnoresUnneededDefaultVersion() throws Exception {
		IFile file = stage();
		repository.getConfig().setString("index", null, "version", "invalid");
		repository.getConfig().setBoolean("index", null, "skipHash", true);
		restage(file);
		footer(repository, true);
	}

	@Test
	public void submoduleUsesItsOwnConfiguration() throws Exception {
		repository.getConfig().setBoolean("index", null, "skipHash", false);
		stage();
		IProject imported = null;
		try (Git git = Git.wrap(repository)) {
			git.commit().setAuthor("Skip Hash", "skip@example.invalid")
					.setCommitter("Skip Hash", "skip@example.invalid")
					.setMessage("base").call();
			try (Repository child = git.submoduleAdd().setPath("sub")
					.setURI(repository.getDirectory().toURI().toString())
					.call()) {
				child.getConfig().setBoolean("index", null, "skipHash", true);
				child.getConfig().save();
				Files.delete(child.getIndexFile().toPath());
				imported = ResourcesPlugin.getWorkspace().getRoot()
						.getProject("skipHashSub" + System.nanoTime());
				var description = ResourcesPlugin.getWorkspace()
						.newProjectDescription(imported.getName());
				description.setLocationURI(child.getWorkTree().toURI());
				imported.create(description, null);
				imported.open(null);
				new ConnectProviderOperation(imported, child.getDirectory())
						.execute(null);
				imported.refreshLocal(IResource.DEPTH_INFINITE, null);
				new AddToIndexOperation(
						new IResource[] { imported.getFile("a") })
						.execute(null);
				footer(child, true);
				footer(repository, false);
				assertEquals(1, child.readDirCache().getEntryCount());
			}
		} finally {
			if (imported != null && imported.exists())
				imported.delete(false, true, null);
		}
	}

	@Test
	public void skippedChecksumStillRefreshesStagingCache() throws Exception {
		IFile file = stage();
		try (Git git = Git.wrap(repository)) {
			git.commit().setAuthor("Skip Hash", "skip@example.invalid")
					.setCommitter("Skip Hash", "skip@example.invalid")
					.setMessage("base").call();
		}
		IndexDiffCacheEntry entry = new IndexDiffCacheEntry(repository, null);
		try {
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			repository.getConfig().setBoolean("index", null, "skipHash", true);
			restage(file);
			footer(repository, true);
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
			assertEquals(Set.of("a"), entry.getIndexDiff().getChanged());
		} finally {
			entry.dispose();
			TestUtils.waitForJobs(10000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
		}
	}
}
