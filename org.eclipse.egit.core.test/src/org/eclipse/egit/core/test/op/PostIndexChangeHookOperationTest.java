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
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.egit.core.op.DiscardChangesOperation;
import org.eclipse.egit.core.op.ResetOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** C Git t7113 scenarios executed through actual EGit operations. */
public class PostIndexChangeHookOperationTest extends GitTestCase {
	private static Path traceFile;

	private static int traceOffset;

	@org.junit.BeforeClass
	public static void initializeTrace() throws Exception {
		String target = System.getenv("GIT_TRACE2_EVENT");
		if (target != null && Path.of(target).isAbsolute()) {
			assertTrue(org.eclipse.jgit.util.Trace2.getInstance().isEnabled());
			traceFile = Path.of(target);
			traceOffset = Files.readAllLines(traceFile).size();
		}
	}

	@org.junit.AfterClass
	public static void verifyOperationHookTrace() throws Exception {
		if (traceFile == null) {
			return;
		}
		var stacks = new java.util.HashMap<String, java.util.List<com.google.gson.JsonObject>>();
		var children = new java.util.HashMap<String, com.google.gson.JsonObject>();
		int starts = 0;
		int failedNotifications = 0;
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
				assertFalse(stack.isEmpty());
				var entered = stack.remove(stack.size() - 1);
				assertEquals(entered.get("category"), event.get("category"));
				assertEquals(entered.get("label"), event.get("label"));
				break;
			case "child_start":
				if (!event.has("hook_name") || !"post-index-change"
						.equals(event.get("hook_name").getAsString())) {
					break;
				}
				assertEquals("hook", event.get("child_class").getAsString());
				assertFalse(stack.isEmpty());
				assertEquals("post-index-change",
						stack.get(stack.size() - 1).get("label").getAsString());
				assertTrue(stack.stream().anyMatch(
						r -> "egit".equals(r.get("category").getAsString())));
				assertTrue(stack.stream().anyMatch(
						r -> "jgit".equals(r.get("category").getAsString())));
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
					if (code == 7) {
						failedNotifications++;
					}
				}
				break;
			default:
				break;
			}
		}
		assertEquals(10, starts);
		assertEquals(1, failedNotifications);
		assertTrue(children.isEmpty());
		assertTrue(stacks.values().stream().allMatch(java.util.List::isEmpty));
	}

	private TestRepository testRepository;

	private Repository db;

	private Git git;

	private Path record;

	private RevCommit initial;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		testRepository = new TestRepository(gitDir);
		db = testRepository.getRepository();
		testRepository.connect(project.getProject());
		git = Git.wrap(db);
		writeFile("initial");
		git.add().addFilepattern(".").call();
		initial = git.commit().setMessage("initial").call();
		record = db.getDirectory().toPath().resolve("index-hook.args");
		installHook(0);
	}

	@Override
	@After
	public void tearDown() throws Exception {
		if (testRepository != null) {
			testRepository.dispose();
		}
		super.tearDown();
	}

	private Path workFile() {
		return project.getProject().getLocation().toFile().toPath()
				.resolve("a");
	}

	private void writeFile(String content) throws Exception {
		Files.writeString(workFile(), content);
		project.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
	}

	private void add() throws Exception {
		new AddToIndexOperation(
				new IResource[] { project.getProject().getFile("a") })
				.execute(null);
	}

	private Path installHook(int code) throws Exception {
		Path hook = db.getDirectory().toPath()
				.resolve("hooks/post-index-change");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook, "#!/bin/sh\n"
				+ "test ! -e \"$GIT_DIR/index.lock\" || exit 91\n"
				+ "test -f \"$GIT_DIR/index\" || exit 92\n"
				+ "printf '%s %s\\n' \"$1\" \"$2\" >>\"$GIT_DIR/index-hook.args\"\n"
				+ "exit " + code + "\n");
		assertTrue(hook.toFile().setExecutable(true));
		return hook;
	}

	private void check(String flags) throws Exception {
		assertEquals(flags + "\n", Files.readString(record));
		assertFalse(Files.exists(
				db.getIndexFile().toPath().resolveSibling("index.lock")));
	}

	@Test
	public void addNotifiesAfterUnlock() throws Exception {
		writeFile("changed");
		add();
		check("0 0");
		assertTrue(git.status().call().getChanged().contains(
				testRepository.getRepoRelativePath(workFile().toString())));
	}

	@Test
	public void checkoutBranchNotifiesWorkTreeUpdate() throws Exception {
		new BranchOperation(db, db.getFullBranch()).execute(null);
		check("1 0");
	}

	@Test
	public void checkoutDetachedNotifiesWorkTreeUpdate() throws Exception {
		new BranchOperation(db, initial.name()).execute(null);
		check("1 0");
	}

	@Test
	public void createBranchNotifiesWorkTreeUpdate() throws Exception {
		git.branchCreate().setName("test").call();
		new BranchOperation(db, "refs/heads/test").execute(null);
		check("1 0");
	}

	@Test
	public void checkoutPathNotifiesWithoutFlags() throws Exception {
		writeFile("changed");
		new DiscardChangesOperation(db,
				List.of(testRepository
						.getRepoRelativePath(workFile().toString())))
				.execute(null);
		check("0 0");
		assertEquals("initial", Files.readString(workFile()));
	}

	@Test
	public void hardResetNotifiesWorkTreeUpdate() throws Exception {
		writeFile("changed");
		new ResetOperation(db, Constants.HEAD, ResetType.HARD).execute(null);
		check("1 0");
		assertEquals("initial", Files.readString(workFile()));
	}

	@Test
	public void mixedResetNotifiesPossibleSkipWorkTreeChange()
			throws Exception {
		writeFile("changed");
		add();
		Files.delete(record);
		new ResetOperation(db, Constants.HEAD, ResetType.MIXED).execute(null);
		check("0 1");
		assertTrue(git.status().call().getModified().contains(
				testRepository.getRepoRelativePath(workFile().toString())));
	}

	@Test
	public void softResetDoesNotNotify() throws Exception {
		new ResetOperation(db, Constants.HEAD, ResetType.SOFT).execute(null);
		assertFalse(Files.exists(record));
	}

	@Test
	public void failedNotificationDoesNotUndoWrite() throws Exception {
		installHook(7);
		writeFile("changed");
		add();
		check("0 0");
		assertTrue(git.status().call().getChanged().contains(
				testRepository.getRepoRelativePath(workFile().toString())));
	}

	@Test
	public void configuredHookRunsWithoutTraditionalHook() throws Exception {
		Path hook = installHook(0);
		Path command = hook.resolveSibling("index-notification");
		Files.move(hook, command);
		db.getConfig().setString("hook", "index notification", "event",
				"post-index-change");
		db.getConfig().setString("hook", "index notification", "command",
				"'" + command + "'");
		add();
		check("0 0");
	}

	@Test
	public void nonExecutableHookDoesNotRun() throws Exception {
		assertTrue(installHook(0).toFile().setExecutable(false, false));
		add();
		assertFalse(Files.exists(record));
	}
}
