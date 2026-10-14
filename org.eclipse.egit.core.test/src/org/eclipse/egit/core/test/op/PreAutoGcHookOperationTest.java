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
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.egit.core.op.GarbageCollectOperation;
import org.eclipse.egit.core.op.MergeOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** t6500 automatic-GC hook boundaries through actual EGit operations. */
public class PreAutoGcHookOperationTest extends GitTestCase {
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
				if (!event.has("hook_name") || !"pre-auto-gc"
						.equals(event.get("hook_name").getAsString()))
					break;
				assertEquals("hook", event.get("child_class").getAsString());
				assertFalse(stack.isEmpty());
				assertEquals("pre-auto-gc",
						stack.get(stack.size() - 1).get("label").getAsString());
				assertTrue(stack.stream().anyMatch(
						r -> "jgit".equals(r.get("category").getAsString())
								&& java.util.Set.of("merge").contains(
										r.get("label").getAsString())));
				assertTrue(stack.stream().anyMatch(
						r -> "egit".equals(r.get("category").getAsString())
								&& java.util.Set.of("MergeOperation").contains(
										r.get("label").getAsString())));
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
					assertTrue(code == 0 || code == 1 || code == 7);
					if (code != 0)
						rejected++;
				}
				break;
			default:
				break;
			}
		}
		assertEquals(3, starts);
		assertEquals(2, rejected);
		assertTrue(children.isEmpty());
	}

	private TestRepository testRepository;

	private Repository repository;

	private RevCommit target;

	private Path record;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		testRepository = new TestRepository(gitDir);
		repository = testRepository.getRepository();
		testRepository.connect(project.getProject());
		repository.getConfig().setInt("gc", null, "auto", 0);
		repository.getConfig().setBoolean("gc", null, "autoDetach", false);
		Git git = Git.wrap(repository);
		RevCommit first = null;
		for (String text : new String[] { "263", "410" }) {
			Files.writeString(repository.getWorkTree().toPath().resolve(text),
					text + "\n");
			git.add().addFilepattern(text).call();
			target = git.commit().setMessage(text).call();
			if (first == null)
				first = target;
		}
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(first.name())
				.call();
		Files.writeString(repository.getWorkTree().toPath().resolve("local"),
				"local\n");
		git.add().addFilepattern("local").call();
		git.commit().setMessage("local").call();
		repository.getConfig().setInt("gc", null, "auto", 3);
		record = repository.getDirectory().toPath().resolve("pre-auto-gc.args");
	}

	@Override
	@After
	public void tearDown() throws Exception {
		if (testRepository != null)
			testRepository.dispose();
		super.tearDown();
	}

	private void hook(int exit) throws Exception {
		Path hook = repository.getDirectory().toPath()
				.resolve("hooks/pre-auto-gc");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook,
				"#!/bin/sh\nprintf '%s\\n' \"$#\" >>\"$GIT_DIR/pre-auto-gc.args\"\nexit "
						+ exit + "\n");
		assertTrue(FS.DETECTED.setExecute(hook.toFile(), true));
	}

	private long loose() throws Exception {
		Path bucket = repository.getDirectory().toPath().resolve("objects/17");
		if (!Files.exists(bucket))
			return 0;
		try (var files = Files.list(bucket)) {
			return files.filter(Files::isRegularFile).count();
		}
	}

	private void merge() throws Exception {
		MergeOperation operation = new MergeOperation(repository,
				target.name());
		operation.setMessage("merge");
		operation.execute(null);
		assertEquals(MergeResult.MergeStatus.MERGED,
				operation.getResult().getMergeStatus());
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
			assertEquals(target,
					walk.parseCommit(repository.resolve("HEAD")).getParent(1));
		}
	}

	@Test
	public void rejectionSkipsGcButDoesNotRejectMerge() throws Exception {
		hook(1);
		long before = loose();
		merge();
		assertEquals("0\n", Files.readString(record));
		assertEquals(before, loose());
	}

	@Test
	public void acceptingHookPermitsGcAfterMerge() throws Exception {
		hook(0);
		merge();
		assertEquals("0\n", Files.readString(record));
		assertEquals(0, loose());
	}

	@Test
	public void belowThresholdDoesNotInvokeHook() throws Exception {
		hook(1);
		repository.getConfig().setInt("gc", null, "auto", 6700);
		merge();
		assertFalse(Files.exists(record));
	}

	@Test
	public void explicitGarbageCollectOperationDoesNotInvokeHook()
			throws Exception {
		hook(1);
		new GarbageCollectOperation(repository).execute(null);
		assertFalse(Files.exists(record));
		assertEquals(0, loose());
	}

	@Test
	public void configuredRejectionSkipsGcAfterMerge() throws Exception {
		repository.getConfig().setString("hook", "prevent", "event",
				"pre-auto-gc");
		repository.getConfig().setString("hook", "prevent", "command",
				"echo configured >>\"$GIT_DIR/pre-auto-gc.args\"; exit 7");
		long before = loose();
		merge();
		assertEquals("configured\n", Files.readString(record));
		assertEquals(before, loose());
	}
}
