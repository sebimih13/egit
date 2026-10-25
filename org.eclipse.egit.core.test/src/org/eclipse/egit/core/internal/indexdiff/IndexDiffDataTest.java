/*******************************************************************************
 * Copyright (C) 2015 Andrey Loskutov <loskutov@gmx.de> and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import static java.util.Arrays.asList;
import static org.eclipse.egit.core.internal.indexdiff.IndexDiffData.isAnyPrefixOf;
import static org.eclipse.egit.core.internal.indexdiff.IndexDiffData.mergeIgnored;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.egit.core.test.GitTestCase;
import org.junit.Test;

public class IndexDiffDataTest extends GitTestCase {
	@Test
	public void directoryRefreshMergesAllCategoriesAndRetainsNeighborConflicts()
			throws Exception {
		var fixture = new org.eclipse.egit.core.test.TestRepository(new java.io.File(
				project.getProject().getLocation().toFile(), ".git"));
		var repository = fixture.getRepository();
		var root = repository.getWorkTree().toPath();
		try (var git = org.eclipse.jgit.api.Git.wrap(repository)) {
			for (String path : java.util.List.of("dir/tracked", "dir/staged", "dir/missing",
					"dir/removed", "dir/resolved", "dir-other/keep")) {
				java.nio.file.Files.createDirectories(root.resolve(path).getParent());
				java.nio.file.Files.writeString(root.resolve(path), "base\n");
			}
			git.add().addFilepattern(".").call();
			git.commit().setAuthor("Merge Test", "merge@example.invalid")
					.setCommitter("Merge Test", "merge@example.invalid").setMessage("base").call();
			var index = repository.lockDirCache();
			try {
				var builder = index.builder();
				for (int i = 0; i < index.getEntryCount(); i++) {
					var entry = index.getEntry(i);
					if (Set.of("dir/resolved", "dir-other/keep").contains(entry.getPathString())) {
						for (int stage = 1; stage <= 3; stage++) {
							var conflict = new org.eclipse.jgit.dircache.DirCacheEntry(entry.getPathString(), stage);
							conflict.setFileMode(entry.getFileMode());
							conflict.setObjectId(entry.getObjectId());
							builder.add(conflict);
						}
					} else builder.add(new org.eclipse.jgit.dircache.DirCacheEntry(entry));
				}
				assertTrue(builder.commit());
			} finally {
				index.unlock();
			}
			java.nio.file.Files.createDirectories(root.resolve("dir/free"));
			java.nio.file.Files.writeString(root.resolve("dir/free/old"), "untracked\n");
			var before = new org.eclipse.jgit.lib.IndexDiff(repository, "HEAD",
					new org.eclipse.jgit.treewalk.FileTreeIterator(repository));
			before.diff();
			var base = new IndexDiffData(before);
			assertTrue(base.getUntrackedFolders().contains("dir/free/"));
			java.nio.file.Files.writeString(root.resolve("dir/staged"), "staged\n");
			java.nio.file.Files.writeString(root.resolve("dir/added"), "added\n");
			git.add().addFilepattern("dir/staged").addFilepattern("dir/added")
					.addFilepattern("dir/free/old").addFilepattern("dir/resolved").call();
			git.rm().addFilepattern("dir/removed").call();
			java.nio.file.Files.writeString(root.resolve("dir/tracked"), "modified\n");
			java.nio.file.Files.delete(root.resolve("dir/missing"));
			java.nio.file.Files.createDirectories(root.resolve("dir/fresh"));
			java.nio.file.Files.writeString(root.resolve("dir/fresh/new"), "untracked\n");
			var changed = new org.eclipse.jgit.lib.IndexDiff(repository, "HEAD",
					new org.eclipse.jgit.treewalk.FileTreeIterator(repository));
			changed.setFilter(org.eclipse.jgit.treewalk.filter.PathFilterGroup.createFromStrings(Set.of("dir/")));
			changed.diff();
			var merged = new IndexDiffData(base, Set.of("dir/"), java.util.List.of(), changed);
			var complete = new org.eclipse.jgit.lib.IndexDiff(repository, "HEAD",
					new org.eclipse.jgit.treewalk.FileTreeIterator(repository));
			complete.diff();
			var expected = new IndexDiffData(complete);
			assertEquals(expected.getAdded(), merged.getAdded());
			assertEquals(expected.getChanged(), merged.getChanged());
			assertEquals(expected.getRemoved(), merged.getRemoved());
			assertEquals(expected.getModified(), merged.getModified());
			assertEquals(expected.getMissing(), merged.getMissing());
			assertEquals(expected.getUntracked(), merged.getUntracked());
			assertEquals(expected.getUntrackedFolders(), merged.getUntrackedFolders());
			assertEquals(expected.getConflicting(), merged.getConflicting());
			assertEquals(expected.getConflictStates(), merged.getConflictStates());
			assertEquals(expected.getIgnoredNotInIndex(), merged.getIgnoredNotInIndex());
			assertEquals(Set.of("dir-other/keep"), merged.getConflicting());
		} finally {
			fixture.dispose();
		}
	}


	@Test
	public void testIsAnyPrefixOf() {
		Collection<String> possiblePrefixes = asList("", "/");
		assertTrue(isAnyPrefixOf("", possiblePrefixes));

		possiblePrefixes = asList("", "/");
		assertTrue(isAnyPrefixOf("/", possiblePrefixes));

		possiblePrefixes = asList("a");
		assertTrue(isAnyPrefixOf("a", possiblePrefixes));

		possiblePrefixes = asList("a/");
		assertTrue(isAnyPrefixOf("a", possiblePrefixes));

		possiblePrefixes = asList("b");
		assertFalse(isAnyPrefixOf("a", possiblePrefixes));

		possiblePrefixes = asList("b", "ab", "b/", "aa");
		assertFalse(isAnyPrefixOf("a", possiblePrefixes));
	}

	@Test
	public void testMergeIgnored() {
		Set<String> result;
		Set<String> expected = new HashSet<>();
		Set<String> oldIgnoredPaths = new HashSet<>();
		Collection<String> changedPaths = new HashSet<>();
		Set<String> newIgnoredPaths = new HashSet<>();

		result = mergeIgnored(oldIgnoredPaths, changedPaths, newIgnoredPaths);
		assertEquals(expected, result);

		newIgnoredPaths.add("a");
		changedPaths.add("a");
		expected.add("a");
		result = mergeIgnored(oldIgnoredPaths, changedPaths, newIgnoredPaths);
		assertEquals(expected, result);

		newIgnoredPaths.add("b");
		expected.add("b");
		result = mergeIgnored(oldIgnoredPaths, changedPaths, newIgnoredPaths);
		assertEquals(expected, result);

		changedPaths.add("b");
		result = mergeIgnored(oldIgnoredPaths, changedPaths, newIgnoredPaths);
		assertEquals(expected, result);

		oldIgnoredPaths.add("b");
		result = mergeIgnored(oldIgnoredPaths, changedPaths, newIgnoredPaths);
		assertEquals(expected, result);

		oldIgnoredPaths.add("c");
		expected.add("c");
		result = mergeIgnored(oldIgnoredPaths, changedPaths, newIgnoredPaths);
		assertEquals(expected, result);

		newIgnoredPaths.add("b");
		expected.add("b");
		result = mergeIgnored(oldIgnoredPaths, changedPaths, newIgnoredPaths);
		assertEquals(expected, result);
	}
}
