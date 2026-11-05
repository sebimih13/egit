/*
 * Copyright (C) 2006 Josh England, 2026 and others
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
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.egit.core.op.MergeOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/** C Git t5402 hook scenarios executed through EGit MergeOperation. */
public class PostMergeHookOperationTest extends GitTestCase {

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
				if (!event.has("hook_name") || !"post-merge"
						.equals(event.get("hook_name").getAsString()))
					break;
				assertEquals("hook", event.get("child_class").getAsString());
				assertFalse(stack.isEmpty());
				assertEquals("post-merge",
						stack.get(stack.size() - 1).get("label").getAsString());
				assertTrue(stack.stream().anyMatch(r -> "jgit"
						.equals(r.get("category").getAsString())
						&& "merge".equals(r.get("label").getAsString())));
				assertTrue(stack.stream().anyMatch(
						r -> "egit".equals(r.get("category").getAsString())
								&& "MergeOperation"
										.equals(r.get("label").getAsString())));
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
					assertTrue(code == 0 || code == 7);
					if (code == 7)
						rejected++;
				}
				break;
			default:
				break;
			}
		}
		assertEquals(5, starts);
		assertEquals(1, rejected);
		assertTrue(children.isEmpty());
	}

	private TestRepository testRepository;

	private Repository repository;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		gitDir = new File(project.getProject().getLocationURI().getPath(),
				Constants.DOT_GIT);
		testRepository = new TestRepository(gitDir);
		repository = testRepository.getRepository();
		testRepository.connect(project.getProject());
		prepareGraph();
	}

	@Override
	@After
	public void tearDown() throws Exception {
		if (testRepository != null)
			testRepository.dispose();
		super.tearDown();
	}

	private MergeResult merge(ObjectId target, boolean squash, boolean commit)
			throws Exception {
		MergeOperation operation = new MergeOperation(repository,
				target.name());
		operation.setSquash(squash);
		operation.setCommit(commit);
		operation.setMessage("merge\n");
		operation.execute(null);
		return operation.getResult();
	}

	private Git git;

	private RevCommit commit0;

	private RevCommit commit1;

	private Path record;

	private void prepareGraph() throws Exception {
		git = Git.wrap(repository);
		Files.writeString(repository.getWorkTree().toPath().resolve("a"),
				"Data for commit0.\n");
		git.add().addFilepattern("a").call();
		commit0 = git.commit().setMessage("setup").call();
		Files.writeString(repository.getWorkTree().toPath().resolve("a"),
				"Changed data for commit1.\n");
		git.add().addFilepattern("a").call();
		commit1 = git.commit().setMessage("modify").call();
		// Native t5402 leaves HEAD at commit0 and the effective index/worktree
		// at commit1.
		git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(commit0.name())
				.call();
		record = repository.getDirectory().toPath().resolve("post-merge.args");
		installHook(0);
	}

	private void installHook(int exit) throws Exception {
		Path hook = repository.getDirectory().toPath()
				.resolve("hooks/post-merge");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook,
				"#!/bin/sh\nprintf '%s\\n' \"$@\" >>\"$GIT_DIR/post-merge.args\"\nexit "
						+ exit + "\n");
		assertTrue(hook.toFile().setExecutable(true));
	}

	private void diverge(boolean conflict) throws Exception {
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit0.name())
				.call();
		Files.writeString(
				repository.getWorkTree().toPath().resolve(conflict ? "a" : "b"),
				"local\n");
		git.add().addFilepattern(conflict ? "a" : "b").call();
		git.commit().setMessage("local").call();
	}

	@Test
	public void upToDateDoesNotRunHook() throws Exception {
		assertEquals(MergeStatus.ALREADY_UP_TO_DATE,
				merge(commit0, false, true).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void normalMergeReceivesZero() throws Exception {
		assertEquals(MergeStatus.FAST_FORWARD,
				merge(commit1, false, true).getMergeStatus());
		assertEquals("0\n", Files.readString(record));
		assertEquals(commit1, repository.resolve(Constants.HEAD));
		assertEquals("Changed data for commit1.\n", Files
				.readString(repository.getWorkTree().toPath().resolve("a")));
	}

	@Test
	public void squashReceivesOneAndKeepsHead() throws Exception {
		assertEquals(MergeStatus.FAST_FORWARD_SQUASHED,
				merge(commit1, true, false).getMergeStatus());
		assertEquals("1\n", Files.readString(record));
		assertEquals(commit0, repository.resolve(Constants.HEAD));
		assertTrue(git.status().call().getChanged().contains("a"));
	}

	@Test
	public void notificationFailureDoesNotUndoMerge() throws Exception {
		installHook(7);
		assertEquals(MergeStatus.FAST_FORWARD,
				merge(commit1, false, true).getMergeStatus());
		assertEquals("0\n", Files.readString(record));
		assertEquals(commit1, repository.resolve(Constants.HEAD));
	}

	@Test
	public void nonFastForwardMergeNotifiesOnce() throws Exception {
		diverge(false);
		assertEquals(MergeStatus.MERGED,
				merge(commit1, false, true).getMergeStatus());
		assertEquals("0\n", Files.readString(record));
	}

	@Test
	public void stoppedBeforeCommitDoesNotNotify() throws Exception {
		diverge(false);
		ObjectId before = repository.resolve(Constants.HEAD);
		assertEquals(MergeStatus.MERGED_NOT_COMMITTED,
				merge(commit1, false, false).getMergeStatus());
		assertFalse(Files.exists(record));
		assertEquals(before, repository.resolve(Constants.HEAD));
	}

	@Test
	public void normalConflictDoesNotNotify() throws Exception {
		diverge(true);
		assertEquals(MergeStatus.CONFLICTING,
				merge(commit1, false, true).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void squashConflictStillNotifies() throws Exception {
		diverge(true);
		ObjectId before = repository.resolve(Constants.HEAD);
		assertEquals(MergeStatus.CONFLICTING,
				merge(commit1, true, false).getMergeStatus());
		assertEquals("1\n", Files.readString(record));
		assertEquals(before, repository.resolve(Constants.HEAD));
	}

}
