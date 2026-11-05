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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.egit.core.op.PushOperation;
import org.eclipse.egit.core.op.PushOperationSpecification;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** C Git t5401 receive scenarios triggered by the actual EGit PushOperation. */
public class ReceiveHookPushOperationTest extends GitTestCase {
	private static Path traceFile;

	private static int traceOffset;

	@org.junit.BeforeClass
	public static void initializeRealTrace() throws Exception {
		String target = System.getenv("GIT_TRACE2_EVENT");
		if (target != null && Path.of(target).isAbsolute()) {
			assertTrue(org.eclipse.jgit.util.Trace2.getInstance().isEnabled());
			traceFile = Path.of(target);
			traceOffset = Files.readAllLines(traceFile).size();
		}
	}

	@org.junit.AfterClass
	public static void verifyReceiveHookEvents() throws Exception {
		if (traceFile == null) {
			return;
		}
		var pending = new java.util.HashMap<String, String>();
		var names = java.util.Set.of("pre-receive", "update", "post-receive", "post-update");
		var lines = Files.readAllLines(traceFile);
		int starts = 0;
		int failures = 0;
		for (String line : lines.subList(traceOffset, lines.size())) {
			var event = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
			String key = event.get("sid").getAsString() + ':' + event.get("child_id");
			switch (event.get("event").getAsString()) {
			case "child_start":
				if (event.has("hook_name") && names.contains(event.get("hook_name").getAsString())) {
					assertEquals("hook", event.get("child_class").getAsString());
					assertEquals(null, pending.put(key, event.get("hook_name").getAsString()));
					starts++;
				}
				break;
			case "child_exit":
				if (pending.remove(key) != null) {
					assertTrue(event.get("pid").getAsLong() > 0);
					assertTrue(event.get("t_rel").getAsDouble() >= 0);
					int code = event.get("code").getAsInt();
					assertTrue(code == 0 || code == 1 || code == 7 || code == 9);
					if (code != 0) {
						failures++;
					}
				}
				break;
			default:
				break;
			}
		}
		assertEquals(22, starts);
		assertEquals(4, failures);
		assertTrue(pending.isEmpty());
	}

	private TestRepository testRepository;

	private Repository db;

	private Repository server;

	private URIish uri;

	private RevCommit first;

	private RevCommit second;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		testRepository = new TestRepository(gitDir);
		db = testRepository.getRepository();
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		server = org.eclipse.jgit.storage.file.FileRepositoryBuilder
				.create(testUtils.createTempDir("receive-server"));
		server.create(true);
		try (Git git = Git.wrap(db)) {
			Files.writeString(db.getWorkTree().toPath().resolve("a"),
					"This is a test.\n");
			git.add().addFilepattern("a").call();
			first = git.commit().setMessage("setup").call();
			Files.writeString(db.getWorkTree().toPath().resolve("a"),
					"We hope it works.\n");
			git.add().addFilepattern("a").call();
			second = git.commit().setMessage("modify").call();
		}
		uri = new URIish(server.getDirectory().toURI().toString());
		push("refs/heads/main", first, "refs/heads/tofail", second);
	}

	@Override
	@After
	public void tearDown() throws Exception {
		if (server != null) {
			server.close();
		}
		if (testRepository != null) {
			testRepository.dispose();
		}
		super.tearDown();
	}

	private PushResult push(String ref, ObjectId id, String otherRef,
			ObjectId otherId) throws Exception {
		List<RemoteRefUpdate> updates = new ArrayList<>();
		updates.add(new RemoteRefUpdate(db, null, id, ref, true, null, null));
		if (otherRef != null) {
			updates.add(new RemoteRefUpdate(db, null, otherId, otherRef, true,
					null, null));
		}
		PushOperationSpecification specification = new PushOperationSpecification();
		specification.addURIRefUpdates(uri, updates);
		PushOperation operation = new PushOperation(db, specification, false,
				0);
		operation.run(null);
		PushResult result = operation.getOperationResult().getPushResult(uri);
		assertTrue("PushOperation did not return a result", result != null);
		return result;
	}

	private Path hook(String name, String body) throws Exception {
		Path path = server.getDirectory().toPath().resolve("hooks/" + name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, "#!/bin/sh\n" + body);
		assertTrue(path.toFile().setExecutable(true));
		return path;
	}

	private void installHooks(String rejected) throws Exception {
		hook("pre-receive", "printf %s \"$@\" >\"$GIT_DIR/pre-receive.args\"\n"
				+ "cat >\"$GIT_DIR/pre-receive.stdin\"\n");
		hook("update", "echo \"$@\" >>\"$GIT_DIR/update.args\"\n"
				+ "cat >\"$GIT_DIR/update.stdin\"\n" + (rejected == null ? ""
						: "test \"$1\" != " + rejected + "\n"));
		hook("post-receive",
				"printf %s \"$@\" >\"$GIT_DIR/post-receive.args\"\n"
						+ "cat >\"$GIT_DIR/post-receive.stdin\"\n");
		hook("post-update", "echo \"$@\" >\"$GIT_DIR/post-update.args\"\n"
				+ "cat >\"$GIT_DIR/post-update.stdin\"\n");
	}

	private String record(String path) throws Exception {
		return Files.readString(server.getDirectory().toPath().resolve(path));
	}

	private boolean recorded(String path) {
		return Files.exists(server.getDirectory().toPath().resolve(path));
	}

	private String line(ObjectId oldId, ObjectId newId, String ref) {
		return oldId.name() + " " + newId.name() + " " + ref + "\n";
	}

	@Test
	public void partialRejectionFiltersPostNotifications() throws Exception {
		installHooks("refs/heads/tofail");
		PushResult result = push("refs/heads/main", second, "refs/heads/tofail",
				first);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(second, server.resolve("refs/heads/main"));
		assertEquals(second, server.resolve("refs/heads/tofail"));
		assertEquals(
				line(first, second, "refs/heads/main")
						+ line(second, first, "refs/heads/tofail"),
				record("pre-receive.stdin"));
		assertEquals("refs/heads/main " + first.name() + " " + second.name()
				+ "\n" + "refs/heads/tofail " + second.name() + " "
				+ first.name() + "\n", record("update.args"));
		assertEquals(line(first, second, "refs/heads/main"),
				record("post-receive.stdin"));
		assertEquals("refs/heads/main\n", record("post-update.args"));
		assertEquals("", record("pre-receive.args"));
		assertEquals("", record("post-receive.args"));
		assertEquals("", record("update.stdin"));
		assertEquals("", record("post-update.stdin"));
	}

	@Test
	public void preReceiveFailureRejectsAllUpdates() throws Exception {
		installHooks(null);
		hook("pre-receive", "cat >\"$GIT_DIR/pre-receive.stdin\"\nexit 7\n");
		PushResult result = push("refs/heads/main", second, "refs/heads/tofail",
				first);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(first, server.resolve("refs/heads/main"));
		assertFalse(recorded("update.args"));
		assertFalse(recorded("post-receive.stdin"));
		assertFalse(recorded("post-update.args"));
	}

	@Test
	public void postHookFailureCannotUndoUpdate() throws Exception {
		installHooks(null);
		hook("post-receive", "cat >\"$GIT_DIR/post-receive.stdin\"\nexit 7\n");
		hook("post-update",
				"echo \"$@\" >\"$GIT_DIR/post-update.args\"\nexit 9\n");
		PushResult result = push("refs/heads/main", second, null, null);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(second, server.resolve("refs/heads/main"));
		assertEquals(line(first, second, "refs/heads/main"),
				record("post-receive.stdin"));
		assertEquals("refs/heads/main\n", record("post-update.args"));
	}

	@Test
	public void creationAndDeletionUseZeroIds() throws Exception {
		installHooks(null);
		PushResult result = push("refs/heads/new", first, "refs/heads/tofail",
				ObjectId.zeroId());
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/new").getStatus());
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(
				line(ObjectId.zeroId(), first, "refs/heads/new")
						+ line(second, ObjectId.zeroId(), "refs/heads/tofail"),
				record("post-receive.stdin"));
		assertEquals(null, server.resolve("refs/heads/tofail"));
	}

	@Test
	public void noOpPushDoesNotRunHooks() throws Exception {
		installHooks(null);
		push("refs/heads/main", first, null, null);
		assertFalse(recorded("pre-receive.stdin"));
		assertFalse(recorded("post-receive.stdin"));
	}

	@Test
	public void hookOutputIsForwardedToClient() throws Exception {
		hook("pre-receive",
				"cat >/dev/null\necho STDOUT pre-receive\necho STDERR pre-receive >&2\n");
		PushResult result = push("refs/heads/main", second, null, null);
		assertTrue(result.getMessages().contains("STDOUT pre-receive"));
		assertTrue(result.getMessages().contains("STDERR pre-receive"));
	}

	@Test
	public void updateHooksSeeRefsBeforeBatchCommit() throws Exception {
		hook("update",
				"if test \"$1\" = refs/heads/tofail; then\n" + "grep -qx "
						+ first.name()
						+ " \"$GIT_DIR/refs/heads/main\" || exit 7\n" + "fi\n");
		PushResult result = push("refs/heads/main", second, "refs/heads/tofail",
				first);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(first, server.resolve("refs/heads/tofail"));
	}

	@Test
	public void preReceiveRunsBeforeUpdateHooks() throws Exception {
		hook("pre-receive", "cat >/dev/null\necho pre >\"$GIT_DIR/order\"\n");
		hook("update", "test -f \"$GIT_DIR/order\" || exit 7\n");
		PushResult result = push("refs/heads/main", second, null, null);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
	}

	@Test(timeout = 60000)
	public void preReceiveMayIgnoreLargeInput() throws Exception {
		hook("pre-receive", "exit 0\n");
		List<RemoteRefUpdate> updates = new ArrayList<>();
		for (int i = 100; i <= 999; i++) {
			updates.add(new RemoteRefUpdate(db, null, second,
					"refs/heads/branch_" + i, true, null, null));
		}
		{
			PushOperationSpecification specification = new PushOperationSpecification();
			specification.addURIRefUpdates(uri, updates);
			PushOperation operation = new PushOperation(db, specification,
					false, 0);
			operation.run(null);
			PushResult result = operation.getOperationResult()
					.getPushResult(uri);
			assertTrue("PushOperation did not return a result", result != null);
			assertEquals(900, result.getRemoteUpdates().size());
			for (RemoteRefUpdate update : result.getRemoteUpdates()) {
				assertEquals(RemoteRefUpdate.Status.OK, update.getStatus());
				assertEquals(second, server.resolve(update.getRemoteName()));
			}
		}
	}

	@Test
	public void configuredReceiveHookRuns() throws Exception {
		Path path = hook("notification",
				"cat >\"$GIT_DIR/pre-receive.stdin\"\n");
		server.getConfig().setString("hook", "notification", "event",
				"pre-receive");
		server.getConfig().setString("hook", "notification", "command",
				"'" + path + "'");
		server.getConfig().save();
		push("refs/heads/main", second, null, null);
		assertEquals(line(first, second, "refs/heads/main"),
				record("pre-receive.stdin"));
	}

	@Test
	public void nonExecutableReceiveHookDoesNotReject() throws Exception {
		assertTrue(hook("pre-receive", "exit 7\n").toFile().setExecutable(false,
				false));
		PushResult result = push("refs/heads/main", second, null, null);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
	}
}
