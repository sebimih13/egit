/*******************************************************************************
 * Copyright (C) 2006 Josh England, 2026 and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.test.op;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.egit.core.op.DiscardChangesOperation;
import org.eclipse.egit.core.op.RebaseOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Assume;
import org.junit.Test;

/** C Git t5403 scenarios executed through EGit operations. */
public class PostCheckoutHookOperationTest extends GitTestCase {
	private static java.nio.file.Path traceFile;

	private static int traceOffset;

	@BeforeClass
	public static void startTraceBeforeMockSystemReader() throws Exception {
		String target = System.getenv("GIT_TRACE2_EVENT");
		if (target != null && java.nio.file.Path.of(target).isAbsolute()) {
			assertTrue(org.eclipse.jgit.util.Trace2.getInstance().isEnabled());
			traceFile = java.nio.file.Path.of(target);
			traceOffset = Files.readAllLines(traceFile).size();
		}
	}

	@AfterClass
	public static void verifyPostCheckoutTraceContexts() throws Exception {
		if (traceFile == null)
			return;
		var regions = new java.util.HashMap<String, java.util.List<com.google.gson.JsonObject>>();
		var children = new java.util.HashMap<String, com.google.gson.JsonObject>();
		int count = 0;
		var lines = Files.readAllLines(traceFile);
		for (String line : lines.subList(traceOffset, lines.size())) {
			var event = com.google.gson.JsonParser.parseString(line)
					.getAsJsonObject();
			String key = event.get("sid").getAsString() + '/'
					+ event.get("thread").getAsString();
			var stack = regions.computeIfAbsent(key,
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
				if (!event.has("hook_name") || !event.get("hook_name")
						.getAsString().equals("post-checkout"))
					break;
				assertEquals("hook", event.get("child_class").getAsString());
				assertTrue(!stack.isEmpty());
				assertEquals("post-checkout",
						stack.get(stack.size() - 1).get("label").getAsString());
				assertTrue(stack.stream().anyMatch(region -> region
						.get("category").getAsString().equals("jgit")
						&& java.util.Set.of("checkout", "rebase", "clone")
								.contains(region.get("label").getAsString())));
				assertEquals(null,
						children.put(key + ':' + event.get("child_id"), event));
				count++;
				break;
			case "child_exit":
				var started = children
						.remove(key + ':' + event.get("child_id"));
				if (started != null) {
					assertEquals(0, event.get("code").getAsInt());
					assertTrue(event.get("pid").getAsLong() > 0);
					assertTrue(event.get("t_rel").getAsDouble() >= 0);
				}
				break;
			default:
				break;
			}
		}
		assertEquals(11, count);
		assertTrue(children.isEmpty());
	}

	private TestRepository testRepository;

	private Repository repository;

	private Git git;

	private RevCommit two;

	private RevCommit onto;

	private RevCommit three;

	private File args;

	private RevCommit commit(String name) throws Exception {
		File file = new File(repository.getWorkTree(), name + ".t");
		Files.writeString(file.toPath(), name);
		git.add().addFilepattern(name + ".t").call();
		RevCommit result = git.commit().setMessage(name).call();
		git.tag().setName(name).setAnnotated(false).call();
		return result;
	}

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		testRepository = new TestRepository(gitDir);
		repository = testRepository.getRepository();
		testRepository.connect(project.getProject());
		repository.updateRef(Constants.HEAD).link("refs/heads/main");
		git = Git.wrap(repository);
		commit("one");
		two = commit("two");
		onto = commit("rebase-on-me");
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(two.name())
				.call();
		three = commit("three");
		args = new File(repository.getDirectory(), "post-checkout.args");
		File hook = new File(repository.getDirectory(), "hooks/post-checkout");
		Files.createDirectories(hook.toPath().getParent());
		Files.writeString(hook.toPath(),
				"#!/bin/sh\nprintf 'old=%s new=%s flag=%s\\n' \"$1\" \"$2\" \"$3\" > \"$GIT_DIR/post-checkout.args\"\n");
		assertTrue(FS.DETECTED.setExecute(hook, true));
	}

	@Override
	@After
	public void tearDown() throws Exception {
		if (testRepository != null)
			testRepository.dispose();
		super.tearDown();
	}

	private void check(ObjectId oldHead, ObjectId newHead, boolean branch)
			throws Exception {
		assertEquals(
				"old=" + oldHead.name() + " new=" + newHead.name() + " flag="
						+ (branch ? "1" : "0") + "\n",
				Files.readString(args.toPath()));
	}

	@Test
	public void branchSwitchThenRebaseMatchesNativeBranchArgumentScenario()
			throws Exception {
		git.branchCreate().setName("rebase-fast-forward").setStartPoint("three")
				.call();
		new BranchOperation(repository, "refs/tags/two").execute(null);
		Files.delete(args.toPath());
		new BranchOperation(repository, "refs/heads/rebase-fast-forward")
				.execute(null);
		new RebaseOperation(repository, repository.exactRef("refs/tags/two"))
				.execute(null);
		check(two, three, true);
	}

	@Test
	public void branchSwitchForRebasePreservesUntrackedFile() throws Exception {
		git.branchCreate().setName("rebase-fast-forward").setStartPoint("three")
				.call();
		new BranchOperation(repository, "refs/tags/two").execute(null);
		Files.delete(args.toPath());
		Files.writeString(
				new File(repository.getWorkTree(), "three.t").toPath(),
				"untracked");
		BranchOperation branch = new BranchOperation(repository,
				"refs/heads/rebase-fast-forward");
		branch.execute(null);
		assertEquals(org.eclipse.jgit.api.CheckoutResult.Status.CONFLICTS,
				branch.getResult(repository).getStatus());
		assertFalse(args.exists());
		assertEquals(two, repository.resolve(Constants.HEAD));
		assertEquals("untracked", Files.readString(
				new File(repository.getWorkTree(), "three.t").toPath()));
	}

	@Test
	public void unchangedHeadStillNotifies() throws Exception {
		new BranchOperation(repository, "refs/heads/main").execute(null);
		check(three, three, true);
	}

	@Test
	public void branchCreationAndCheckoutNotifies() throws Exception {
		git.branchCreate().setName("new1").call();
		assertFalse(args.exists());
		new BranchOperation(repository, "refs/heads/new1").execute(null);
		check(three, three, true);
	}

	@Test
	public void changedHeadHasCorrectArguments() throws Exception {
		new BranchOperation(repository, "refs/tags/two").execute(null);
		check(three, two, true);
	}

	@Test
	public void fileCheckoutThroughDiscardChangesUsesZeroFlag()
			throws Exception {
		Files.writeString(
				new File(repository.getWorkTree(), "three.t").toPath(),
				"modified");
		new DiscardChangesOperation(repository, List.of("three.t"))
				.execute(null);
		check(three, three, false);
		assertEquals("three", Files.readString(
				new File(repository.getWorkTree(), "three.t").toPath()));
	}

	@Test
	public void rebaseRewindNotifies() throws Exception {
		new RebaseOperation(repository,
				repository.exactRef("refs/tags/rebase-on-me")).execute(null);
		check(three, onto, true);
	}

	@Test
	public void fastForwardRebaseNotifies() throws Exception {
		new BranchOperation(repository, "refs/tags/two").execute(null);
		Files.delete(args.toPath());
		new RebaseOperation(repository,
				repository.exactRef("refs/tags/rebase-on-me")).execute(null);
		check(two, onto, true);
	}

	private File cloneTemplate() throws Exception {
		File templates = new File(repository.getDirectory(), "templates");
		File hook = new File(templates, "hooks/post-checkout");
		Files.createDirectories(hook.toPath().getParent());
		Files.copy(
				new File(repository.getDirectory(), "hooks/post-checkout")
						.toPath(),
				hook.toPath(),
				java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
		assertTrue(FS.DETECTED.setExecute(hook, true));
		return templates;
	}

	@Test
	public void cloneOperationRunsTemplateHookWithZeroAndClonedHead()
			throws Exception {
		File destination = new File(repository.getDirectory(), "clone");
		org.eclipse.egit.core.op.CloneOperation operation = new org.eclipse.egit.core.op.CloneOperation(
				new org.eclipse.jgit.transport.URIish(
						repository.getWorkTree().toURI().toString()),
				true, null, destination, "refs/heads/main", "origin", 0);
		operation.setTemplateDirectory(cloneTemplate());
		operation.run(new org.eclipse.core.runtime.NullProgressMonitor());
		assertEquals(
				"old=" + ObjectId.zeroId().name() + " new=" + three.name()
						+ " flag=1\n",
				Files.readString(
						new File(destination, ".git/post-checkout.args")
								.toPath()));
		try (Git cloned = Git.open(destination)) {
			assertEquals(three, cloned.getRepository().resolve(Constants.HEAD));
		}
	}

	@Test
	public void noCheckoutCloneOperationDoesNotRunTemplateHook()
			throws Exception {
		File destination = new File(repository.getDirectory(), "clone");
		org.eclipse.egit.core.op.CloneOperation operation = new org.eclipse.egit.core.op.CloneOperation(
				new org.eclipse.jgit.transport.URIish(
						repository.getWorkTree().toURI().toString()),
				true, null, destination, null, "origin", 0);
		operation.setTemplateDirectory(cloneTemplate());
		operation.run(new org.eclipse.core.runtime.NullProgressMonitor());
		assertFalse(new File(destination, ".git/post-checkout.args").exists());
		assertTrue(new File(destination, ".git/hooks/post-checkout").exists());
	}
}
