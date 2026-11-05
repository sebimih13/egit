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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.egit.core.op.RebaseOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RebaseTodoLine;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;

/** Native pre-rebase scenarios through actual EGit operations. */
public class PreRebaseHookOperationTest extends GitTestCase {
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
				if (!event.has("hook_name") || !"pre-rebase"
						.equals(event.get("hook_name").getAsString()))
					break;
				assertEquals("hook", event.get("child_class").getAsString());
				assertFalse(stack.isEmpty());
				assertEquals("pre-rebase",
						stack.get(stack.size() - 1).get("label").getAsString());
				assertTrue(stack.stream().anyMatch(r -> "jgit"
						.equals(r.get("category").getAsString())
						&& "rebase".equals(r.get("label").getAsString())));
				assertTrue(stack.stream().anyMatch(
						r -> "egit".equals(r.get("category").getAsString())
								&& "RebaseOperation"
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
					assertTrue(code == 0 || code == 1);
					if (code == 1)
						rejected++;
				}
				break;
			default:
				break;
			}
		}
		assertEquals(17, starts);
		assertEquals(8, rejected);
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

	private String upstreamArgument() {
		return "refs/heads/main";
	}

	private RebaseResult rebase(boolean interactive, String branch,
			boolean bypass) throws Exception {
		return rebaseWithUpstream("refs/heads/main", interactive, branch,
				bypass, false);
	}

	private RebaseResult rebaseWithUpstream(String upstream,
			boolean interactive, String branch, boolean bypass, boolean force)
			throws Exception {
		var ref = repository.findRef(upstream);
		if (ref == null) {
			ref = new org.eclipse.jgit.lib.ObjectIdRef.Unpeeled(
					org.eclipse.jgit.lib.Ref.Storage.LOOSE, upstream,
					repository.resolve(upstream));
		}
		RebaseOperation operation = interactive
				? new RebaseOperation(repository, ref, PICKS)
				: new RebaseOperation(repository, ref);
		operation.setBranch(branch);
		operation.setNoVerify(bypass);
		operation.setForce(force);
		operation.execute(null);
		if (operation.getResult()
				.getStatus() == RebaseResult.Status.INTERACTIVE_PREPARED) {
			operation = new RebaseOperation(repository,
					RebaseCommand.Operation.PROCESS_STEPS, PICKS);
			operation.execute(null);
		}
		return operation.getResult();
	}

	private RebaseResult resume() throws Exception {
		RebaseOperation operation = new RebaseOperation(repository,
				RebaseCommand.Operation.CONTINUE);
		operation.execute(null);
		return operation.getResult();
	}

	private Git git;

	private RevCommit initial;

	private RevCommit main;

	private RevCommit side;

	private Path record;

	private static final RebaseCommand.InteractiveHandler PICKS = new RebaseCommand.InteractiveHandler() {
		@Override
		public void prepareSteps(List<RebaseTodoLine> steps) {
		}

		@Override
		public String modifyCommitMessage(String message) {
			return message;
		}
	};

	private void graph() throws Exception {
		repository.updateRef(Constants.HEAD).link("refs/heads/main");
		git = Git.wrap(repository);
		write("file", "hello\n");
		initial = git.commit().setMessage("initial").call();
		write("file", "goodbye\n");
		main = git.commit().setMessage("second").call();
		git.checkout().setCreateBranch(true).setName("side")
				.setStartPoint(initial).call();
		write("git", "world\n");
		side = git.commit().setMessage("side").call();
		git.branchCreate().setName("test").call();
		git.checkout().setName("test").call();
		record = repository.getDirectory().toPath().resolve("PRE-REBASE-INPUT");
	}

	private void write(String path, String text) throws Exception {
		Files.writeString(repository.getWorkTree().toPath().resolve(path),
				text);
		git.add().addFilepattern(path).call();
	}

	private void hook(int code, boolean executable) throws Exception {
		Path file = repository.getDirectory().toPath()
				.resolve("hooks/pre-rebase");
		Files.createDirectories(file.getParent());
		Files.writeString(file,
				"#!/bin/sh\nprintf '%s,%s\\n' \"$1\" \"$2\" >>\"$GIT_DIR/PRE-REBASE-INPUT\"\nexit "
						+ code + "\n");
		assertTrue(FS.DETECTED.setExecute(file.toFile(), executable));
	}

	private void done(RebaseResult result) throws Exception {
		assertTrue(result.getStatus().isSuccessful());
		assertEquals("world\n", Files
				.readString(repository.getWorkTree().toPath().resolve("git")));
		assertEquals("goodbye\n", Files
				.readString(repository.getWorkTree().toPath().resolve("file")));
		assertEquals("refs/heads/test", repository.getFullBranch());
		assertEquals(RepositoryState.SAFE, repository.getRepositoryState());
	}

	private void args(String branch) throws Exception {
		assertEquals(upstreamArgument() + "," + (branch == null ? "" : branch)
				+ "\n", Files.readString(record));
	}

	@Test
	public void rebaseWithoutHook() throws Exception {
		done(rebase(false, null, false));
	}

	@Test
	public void interactiveRebaseWithoutHook() throws Exception {
		done(rebase(true, null, false));
	}

	@Test
	public void currentBranchArgument() throws Exception {
		hook(0, true);
		done(rebase(false, null, false));
		args(null);
	}

	@Test
	public void explicitCurrentBranchArgument() throws Exception {
		hook(0, true);
		done(rebase(false, "test", false));
		args("test");
	}

	@Test
	public void explicitOtherBranchArgument() throws Exception {
		hook(0, true);
		git.checkout().setName("main").call();
		done(rebase(false, "test", false));
		args("test");
	}

	@Test
	public void interactiveCurrentBranchArgument() throws Exception {
		hook(0, true);
		done(rebase(true, null, false));
		args(null);
	}

	@Test
	public void interactiveExplicitCurrentBranchArgument() throws Exception {
		hook(0, true);
		done(rebase(true, "test", false));
		args("test");
	}

	@Test
	public void interactiveExplicitOtherBranchArgument() throws Exception {
		hook(0, true);
		git.checkout().setName("main").call();
		done(rebase(true, "test", false));
		args("test");
	}

	private void rejected(boolean interactive) throws Exception {
		hook(1, true);
		assertThrows(CoreException.class,
				() -> rebase(interactive, null, false));
		assertEquals(side, repository.resolve(Constants.HEAD));
		assertEquals("refs/heads/test", repository.getFullBranch());
		assertEquals(RepositoryState.SAFE, repository.getRepositoryState());
		assertFalse(new java.io.File(repository.getDirectory(), "rebase-merge")
				.exists());
		assertFalse(new java.io.File(repository.getDirectory(), "rebase-apply")
				.exists());
	}

	@Test
	public void rejectionStopsNormalRebase() throws Exception {
		rejected(false);
	}

	@Test
	public void rejectionStopsInteractiveRebase() throws Exception {
		rejected(true);
	}

	@Test
	public void noVerifyBypassesNormalRebaseHook() throws Exception {
		hook(1, true);
		done(rebase(false, null, true));
		assertFalse(Files.exists(record));
	}

	@Test
	public void noVerifyBypassesInteractiveRebaseHook() throws Exception {
		hook(1, true);
		done(rebase(true, null, true));
		assertFalse(Files.exists(record));
	}

	@Test
	public void rejectionRestoresAutostash() throws Exception {
		repository.getConfig().setBoolean("rebase", null, "autoStash", true);
		Files.writeString(repository.getWorkTree().toPath().resolve("file"),
				"changed\n");
		rejected(false);
		assertEquals("changed\n", Files
				.readString(repository.getWorkTree().toPath().resolve("file")));
		assertTrue(git.status().call().getModified().contains("file"));
	}

	@Test
	public void dirtyWorktreeStopsBeforeHook() throws Exception {
		hook(1, true);
		Files.writeString(repository.getWorkTree().toPath().resolve("file"),
				"changed\n");
		assertEquals(RebaseResult.Status.UNCOMMITTED_CHANGES,
				rebase(false, null, false).getStatus());
		assertFalse(Files.exists(record));
		assertEquals(side, repository.resolve(Constants.HEAD));
	}

	@Test
	public void dirtyFastForwardStopsBeforeHook() throws Exception {
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(initial.name())
				.call();
		hook(1, true);
		Files.writeString(repository.getWorkTree().toPath().resolve("file"),
				"changed\n");
		assertEquals(RebaseResult.Status.UNCOMMITTED_CHANGES,
				rebase(false, null, false).getStatus());
		assertFalse(Files.exists(record));
		assertEquals(initial, repository.resolve(Constants.HEAD));
	}

	@Test
	public void upToDateNormalRebaseSkipsHook() throws Exception {
		git.checkout().setName("main").call();
		hook(1, true);
		assertEquals(RebaseResult.Status.UP_TO_DATE,
				rebase(false, null, false).getStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void upToDateInteractiveRebaseStillRunsHook() throws Exception {
		git.checkout().setName("main").call();
		hook(1, true);
		assertThrows(CoreException.class, () -> rebase(true, null, false));
		args(null);
		assertEquals(main, repository.resolve(Constants.HEAD));
	}

	@Test
	public void fastForwardCanBeRejectedBeforeCheckout() throws Exception {
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(initial.name())
				.call();
		hook(1, true);
		assertThrows(CoreException.class, () -> rebase(false, null, false));
		args(null);
		assertEquals(initial, repository.resolve(Constants.HEAD));
		assertEquals("hello\n", Files
				.readString(repository.getWorkTree().toPath().resolve("file")));
	}

	@Test
	public void nonExecutableHookIsIgnored() throws Exception {
		hook(1, false);
		done(rebase(false, null, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void explicitBranchRejectionDoesNotSwitchHead() throws Exception {
		git.checkout().setName("main").call();
		hook(1, true);
		assertThrows(CoreException.class, () -> rebase(false, "test", false));
		args("test");
		assertEquals("refs/heads/main", repository.getFullBranch());
		assertEquals(main, repository.resolve(Constants.HEAD));
	}

	@Test
	public void continuationDoesNotInvokeHookAgain() throws Exception {
		write("file", "local\n");
		git.commit().setMessage("local").call();
		hook(0, true);
		assertEquals(RebaseResult.Status.STOPPED,
				rebase(false, null, false).getStatus());
		args(null);
		write("file", "resolved\n");
		hook(1, true);
		assertTrue(resume().getStatus().isSuccessful());
		args(null);
		assertEquals(RepositoryState.SAFE, repository.getRepositoryState());
	}

	private void forcedRejectionRestoresAutostash(boolean interactive)
			throws Exception {
		repository.getConfig().setBoolean("rebase", null, "autoStash", true);
		Files.writeString(repository.getWorkTree().toPath().resolve("file"),
				"changed\n");
		hook(1, true);
		assertThrows(CoreException.class, () -> rebaseWithUpstream("HEAD^",
				interactive, null, false, true));
		assertEquals("HEAD^,\n", Files.readString(record));
		assertEquals(side, repository.resolve(Constants.HEAD));
		assertEquals("refs/heads/test", repository.getFullBranch());
		assertEquals(RepositoryState.SAFE, repository.getRepositoryState());
		assertFalse(new java.io.File(repository.getDirectory(), "rebase-merge")
				.exists());
		assertEquals("changed\n", Files
				.readString(repository.getWorkTree().toPath().resolve("file")));
	}

	@Test
	public void forcedNormalRejectionRestoresAutostash() throws Exception {
		forcedRejectionRestoresAutostash(false);
	}

	@Test
	public void forcedInteractiveRejectionRestoresAutostash() throws Exception {
		forcedRejectionRestoresAutostash(true);
	}

	@Test
	public void invalidBranchDoesNotCreateAutostash() throws Exception {
		repository.getConfig().setBoolean("rebase", null, "autoStash", true);
		Files.writeString(repository.getWorkTree().toPath().resolve("file"),
				"changed\n");
		hook(1, true);
		assertThrows(CoreException.class,
				() -> rebase(false, "missing", false));
		assertFalse(Files.exists(record));
		assertFalse(new java.io.File(repository.getDirectory(), "rebase-merge")
				.exists());
		assertEquals(side, repository.resolve(Constants.HEAD));
		assertEquals("changed\n", Files
				.readString(repository.getWorkTree().toPath().resolve("file")));
	}

	@Test
	public void forceReplaysAnUpToDateCommit() throws Exception {
		hook(0, true);
		((org.eclipse.jgit.junit.MockSystemReader) org.eclipse.jgit.util.SystemReader
				.getInstance()).tick(60);
		assertEquals(RebaseResult.Status.OK,
				rebaseWithUpstream("HEAD^", false, null, false, true)
						.getStatus());
		assertEquals("HEAD^,\n", Files.readString(record));
		assertFalse(side.equals(repository.resolve(Constants.HEAD)));
		assertEquals("hello\n", Files
				.readString(repository.getWorkTree().toPath().resolve("file")));
		assertEquals("world\n", Files
				.readString(repository.getWorkTree().toPath().resolve("git")));
		assertEquals("refs/heads/test", repository.getFullBranch());
		assertEquals(RepositoryState.SAFE, repository.getRepositoryState());
	}

	@Test
	public void forcePersistsAcrossContinuation() throws Exception {
		write("file", "local\n");
		git.commit().setMessage("local").call();
		hook(0, true);
		assertEquals(RebaseResult.Status.STOPPED,
				rebaseWithUpstream(upstreamArgument(), false, null, false, true)
						.getStatus());
		assertTrue(new java.io.File(repository.getDirectory(),
				"rebase-merge/jgit-force-rebase").exists());
		write("file", "resolved\n");
		hook(1, true);
		assertEquals(RebaseResult.Status.OK, resume().getStatus());
		args(null);
		assertEquals(RepositoryState.SAFE, repository.getRepositoryState());
	}
}
