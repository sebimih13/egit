/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.egit.core.test.op;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;

import org.eclipse.egit.core.op.MergeOperation;
import org.eclipse.egit.core.op.CommitOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;

/**
 * C Git t7503 scenarios through actual EGit merge and resolution operations.
 */
public class PreMergeCommitHookOperationTest extends GitTestCase {
	private static Path traceFile;

	private static int traceOffset;

	@BeforeClass
	public static void initializeRealTrace() throws Exception {
		String target = System.getenv("GIT_TRACE2_EVENT");
		if (target != null && Path.of(target).isAbsolute()) {
			assertTrue(org.eclipse.jgit.util.Trace2.getInstance().isEnabled());
			traceFile = Path.of(target);
			traceOffset = Files.readAllLines(traceFile).size();
		}
	}

	@AfterClass
	public static void verifyMergeHookEvents() throws Exception {
		if (traceFile == null)
			return;
		var stacks = new java.util.HashMap<String, java.util.List<com.google.gson.JsonObject>>();
		var children = new java.util.HashMap<String, com.google.gson.JsonObject>();
		int starts = 0;
		int rejected = 0;
		var lines = Files.readAllLines(traceFile);
		for (String line : lines.subList(traceOffset, lines.size())) {
			var event = com.google.gson.JsonParser.parseString(line)
					.getAsJsonObject();
			String key = event.get("sid").getAsString() + '/'
					+ event.get("thread").getAsString();
			var stack = stacks.computeIfAbsent(key,
					value -> new java.util.ArrayList<>());
			switch (event.get("event").getAsString()) {
			case "region_enter":
				stack.add(event);
				break;
			case "region_leave":
				if (!stack.isEmpty())
					stack.remove(stack.size() - 1);
				break;
			case "child_start":
				if (!event.has("hook_name"))
					break;
				assertEquals("hook", event.get("child_class").getAsString());
				assertFalse(stack.isEmpty());
				assertEquals(event.get("hook_name").getAsString(),
						stack.get(stack.size() - 1).get("label").getAsString());
				assertTrue(stack.stream().anyMatch(
						r -> "jgit".equals(r.get("category").getAsString())
								&& java.util.Set.of("merge", "commit").contains(
										r.get("label").getAsString())));
				assertTrue(stack.stream().anyMatch(r -> "egit"
						.equals(r.get("category").getAsString())
						&& java.util.Set.of("MergeOperation", "CommitOperation")
								.contains(r.get("label").getAsString())));
				assertEquals(null,
						children.put(key + ':' + event.get("child_id"), event));
				starts++;
				break;
			case "child_exit":
				if (children
						.remove(key + ':' + event.get("child_id")) != null) {
					assertTrue(event.get("pid").getAsLong() > 0);
					assertTrue(event.get("t_rel").getAsDouble() >= 0);
					int code = event.get("code").getAsInt();
					assertTrue(code == 0 || code == 1 || code == 3);
					if (code != 0)
						rejected++;
				}
				break;
			default:
				break;
			}
		}
		assertEquals(7, starts);
		assertEquals(2, rejected);
		assertTrue(children.isEmpty());
	}

	private TestRepository testRepository;

	private Repository repository;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		testRepository = new TestRepository(gitDir);
		repository = testRepository.getRepository();
		testRepository.connect(project.getProject());
		graph();
	}

	@Override
	@After
	public void tearDown() throws Exception {
		if (testRepository != null)
			testRepository.dispose();
		super.tearDown();
	}

	private MergeResult merge(ObjectId target, boolean noVerify, boolean commit,
			boolean squash) throws Exception {
		MergeOperation operation = new MergeOperation(repository,
				target.name());
		operation.setNoVerify(noVerify);
		operation.setCommit(commit);
		operation.setSquash(squash);
		operation.setMessage("merge main\n");
		operation.execute(null);
		return operation.getResult();
	}

	private void resolve() throws Exception {
		CommitOperation operation = new CommitOperation(null, null,
				TestUtils.AUTHOR, TestUtils.COMMITTER, "resolve conflict\n");
		operation.setRepository(repository);
		operation.setCommitAll(true);
		operation.execute(null);
	}

	private Git git;

	private RevCommit root;

	private RevCommit side;

	private ObjectId main;

	private Path record;

	private void graph() throws Exception {
		repository.updateRef(Constants.HEAD).link("refs/heads/main");
		git = Git.wrap(repository);
		write("file", "root\n");
		root = git.commit().setMessage("zeroth").call();
		git.checkout().setCreateBranch(true).setName("side").call();
		write("foo", "foo\n");
		side = git.commit().setMessage("make it non-ff").call();
		git.branchCreate().setName("side-orig").call();
		git.checkout().setName("main").call();
		write("file", "foo\n");
		main = git.commit().setMessage("first").call();
		record = repository.getDirectory().toPath().resolve("actual_hooks");
	}

	private void write(String path, String content) throws Exception {
		Files.writeString(repository.getWorkTree().toPath().resolve(path),
				content);
		git.add().addFilepattern(path).call();
	}

	private void hook(String name, String body, boolean executable)
			throws Exception {
		Path path = repository.getDirectory().toPath().resolve("hooks/" + name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, "#!/bin/sh\n" + body + "\n");
		assertTrue(FS.DETECTED.setExecute(path.toFile(), executable));
	}

	private void success() throws Exception {
		hook("pre-merge-commit",
				"echo pre-merge-commit >>\"$GIT_DIR/actual_hooks\"", true);
	}

	private void failure() throws Exception {
		hook("pre-merge-commit",
				"echo pre-merge-commit-failing-hook >>\"$GIT_DIR/actual_hooks\"; exit 1",
				true);
	}

	private void checkoutSide() throws Exception {
		git.checkout().setName("side").call();
	}

	private void assertMerged(MergeResult result) throws Exception {
		assertEquals(MergeStatus.MERGED, result.getMergeStatus());
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
			assertEquals(2, walk.parseCommit(repository.resolve(Constants.HEAD))
					.getParentCount());
		}
		assertEquals("foo\n", Files
				.readString(repository.getWorkTree().toPath().resolve("file")));
	}

	@Test
	public void noHookMerge() throws Exception {
		checkoutSide();
		assertMerged(merge(main, false, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void noVerifyWithNoHook() throws Exception {
		checkoutSide();
		assertMerged(merge(main, true, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void succeedingMergeHookDoesNotInvokePreCommit() throws Exception {
		success();
		hook("pre-commit",
				"echo pre-commit >>\"$GIT_DIR/actual_hooks\"; exit 9", true);
		checkoutSide();
		assertMerged(merge(main, false, true, false));
		assertEquals("pre-merge-commit\n", Files.readString(record));
	}

	@Test
	public void noVerifyWithSucceedingHook() throws Exception {
		success();
		checkoutSide();
		assertMerged(merge(main, true, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void failingMergeHookLeavesHeadAndPreparedIndex() throws Exception {
		failure();
		checkoutSide();
		assertThrows(CoreException.class,
				() -> merge(main, false, true, false));
		assertEquals(side, repository.resolve(Constants.HEAD));
		assertEquals("pre-merge-commit-failing-hook\n",
				Files.readString(record));
		assertFalse(new java.io.File(repository.getDirectory(), "index.lock")
				.exists());
		assertTrue(git.status().call().getChanged().contains("file"));
		assertEquals(org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED,
				repository.getRepositoryState());
	}

	@Test
	public void noVerifyBypassesBothVerificationHooksButStillPrepares()
			throws Exception {
		failure();
		hook("commit-msg",
				"echo commit-msg >>\"$GIT_DIR/actual_hooks\"; exit 8", true);
		hook("prepare-commit-msg", "echo prepared >>\"$GIT_DIR/actual_hooks\"",
				true);
		checkoutSide();
		assertMerged(merge(main, true, true, false));
		assertEquals("prepared\n", Files.readString(record));
	}

	@Test
	public void nonExecutableHookIsIgnored() throws Exception {
		hook("pre-merge-commit",
				"echo non-exec >>\"$GIT_DIR/actual_hooks\"; exit 1", false);
		checkoutSide();
		assertMerged(merge(main, false, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void noVerifyWithNonExecutableHook() throws Exception {
		hook("pre-merge-commit",
				"echo non-exec >>\"$GIT_DIR/actual_hooks\"; exit 1", false);
		checkoutSide();
		assertMerged(merge(main, true, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void conflictsSkipBothHooksAndResolutionRunsPreCommit()
			throws Exception {
		git.checkout().setCreateBranch(true).setName("conflicting-a")
				.setStartPoint(root).call();
		write("conflicting", "a\n");
		var a = git.commit().setMessage("conflicting-a").call();
		git.checkout().setCreateBranch(true).setName("conflicting-b")
				.setStartPoint(root).call();
		write("conflicting", "b\n");
		var b = git.commit().setMessage("conflicting-b").call();
		git.checkout().setName("conflicting-a").call();
		success();
		hook("pre-commit", "echo pre-commit >>\"$GIT_DIR/actual_hooks\"", true);
		assertEquals(MergeStatus.CONFLICTING,
				merge(b, false, true, false).getMergeStatus());
		assertFalse(Files.exists(record));
		assertEquals(a, repository.resolve(Constants.HEAD));
		write("conflicting", "a+b\n");
		resolve();
		assertEquals("pre-commit\n", Files.readString(record));
	}

	@Test
	public void mergeHookIndexChangesRemainStagedOutsideCommit() throws Exception {
		hook("pre-merge-commit",
				"test ! -e \"$GIT_INDEX_FILE.lock\" || exit 4; test \"$GIT_EDITOR\" = : || exit 5; "
						+ "echo extra >extra; GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 git add extra",
				true);
		checkoutSide();
		assertMerged(merge(main, false, true, false));
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
			var commit = walk.parseCommit(repository.resolve(Constants.HEAD));
			try (var tree = org.eclipse.jgit.treewalk.TreeWalk
					.forPath(repository, "extra", commit.getTree())) {
				assertNull(tree);
			}
		}
		assertTrue(git.status().call().getAdded().contains("extra"));
	}

	@Test
	public void noCommitDefersVerification() throws Exception {
		failure();
		checkoutSide();
		assertEquals(MergeStatus.MERGED_NOT_COMMITTED,
				merge(main, false, false, false).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void fastForwardSkipsMergeVerification() throws Exception {
		failure();
		git.checkout().setName("main").call();
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(root.name())
				.call();
		assertEquals(MergeStatus.FAST_FORWARD,
				merge(side, false, true, false).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void upToDateSkipsMergeVerification() throws Exception {
		failure();
		checkoutSide();
		assertEquals(MergeStatus.ALREADY_UP_TO_DATE,
				merge(side, false, true, false).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void squashSkipsMergeVerification() throws Exception {
		failure();
		checkoutSide();
		assertEquals(MergeStatus.MERGED_SQUASHED_NOT_COMMITTED,
				merge(main, false, false, true).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void commitMessageVerificationStillRunsAfterMergeVerification()
			throws Exception {
		success();
		hook("commit-msg",
				"echo commit-msg >>\"$GIT_DIR/actual_hooks\"; exit 3", true);
		checkoutSide();
		assertThrows(CoreException.class,
				() -> merge(main, false, true, false));
		assertEquals("pre-merge-commit\ncommit-msg\n",
				Files.readString(record));
		assertEquals(side, repository.resolve(Constants.HEAD));
	}
}
