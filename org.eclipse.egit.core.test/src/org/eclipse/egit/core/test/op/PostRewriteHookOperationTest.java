/*
 * Copyright (C) 2010 Thomas Rast, 2026 and others
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
import java.util.Map;

import org.eclipse.egit.core.op.CommitOperation;
import org.eclipse.egit.core.op.RebaseOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
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

/** Native rewrite scenarios through actual EGit commit/rebase operations. */
public class PostRewriteHookOperationTest extends GitTestCase {
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
				if (!event.has("hook_name") || !"post-rewrite"
						.equals(event.get("hook_name").getAsString()))
					break;
				assertEquals("hook", event.get("child_class").getAsString());
				assertFalse(stack.isEmpty());
				assertEquals("post-rewrite",
						stack.get(stack.size() - 1).get("label").getAsString());
				assertTrue(stack.stream().anyMatch(r -> "jgit"
						.equals(r.get("category").getAsString())
						&& java.util.Set.of("commit", "rebase")
								.contains(r.get("label").getAsString())));
				assertTrue(stack.stream().anyMatch(r -> "egit"
						.equals(r.get("category").getAsString())
						&& java.util.Set
								.of("CommitOperation", "RebaseOperation")
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
					assertTrue(code == 0 || code == 7);
					if (code == 7)
						rejected++;
				}
				break;
			default:
				break;
			}
		}
		assertEquals(17, starts);
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

	private RevCommit amend(boolean suppress, boolean all) throws Exception {
		CommitOperation op = new CommitOperation(repository, TestUtils.AUTHOR,
				TestUtils.COMMITTER, "D new message\n");
		op.setRepository(repository);
		op.setAmending(true);
		op.setNoPostRewrite(suppress);
		op.setCommitAll(all);
		op.execute(null);
		return op.getCommit();
	}

	private RebaseResult rebase(String upstream, String onto, Mode selected)
			throws Exception {
		mode = selected;
		RebaseOperation op = mode == Mode.NORMAL
				? new RebaseOperation(repository, repository.findRef(upstream))
				: new RebaseOperation(repository, repository.findRef(upstream),
						editor);
		op.setOnto(onto);
		op.execute(null);
		if (op.getResult()
				.getStatus() == RebaseResult.Status.INTERACTIVE_PREPARED) {
			op = new RebaseOperation(repository,
					RebaseCommand.Operation.PROCESS_STEPS, editor);
			op.execute(null);
		}
		return op.getResult();
	}

	private RebaseResult resume(RebaseCommand.Operation operation)
			throws Exception {
		RebaseOperation op = mode == Mode.NORMAL
				? new RebaseOperation(repository, operation)
				: new RebaseOperation(repository, operation, editor);
		op.execute(null);
		return op.getResult();
	}

	private Git git;

	private final Map<String, RevCommit> commits = new java.util.LinkedHashMap<>();

	private Path args;

	private Path data;

	private Mode mode = Mode.NORMAL;

	private boolean prepared;

	private enum Mode {
		NORMAL, PICKS, DROP, SQUASH, FIXUP, REWORD
	}

	private final RebaseCommand.InteractiveHandler editor = new RebaseCommand.InteractiveHandler() {
		@Override
		public void prepareSteps(List<RebaseTodoLine> steps) {
			if (prepared)
				return;
			prepared = true;
			switch (mode) {
			case DROP:
				steps.remove(0);
				break;
			case SQUASH:
				setAction(steps.get(1), RebaseTodoLine.Action.SQUASH);
				break;
			case FIXUP:
				setAction(steps.get(1), RebaseTodoLine.Action.FIXUP);
				break;
			case REWORD:
				setAction(steps.get(0), RebaseTodoLine.Action.REWORD);
				break;
			default:
				break;
			}
		}

		@Override
		public String modifyCommitMessage(String message) {
			return "rewritten\n";
		}
	};

	private RevCommit commit(String name, String path, String content)
			throws Exception {
		write(path, content);
		RevCommit result = git.commit().setMessage(name + "\n").call();
		git.tag().setName(name).setAnnotated(false).call();
		commits.put(name, result);
		return result;
	}

	private void write(String path, String content) throws Exception {
		Files.writeString(repository.getWorkTree().toPath().resolve(path),
				content);
		git.add().addFilepattern(path).call();
	}

	private void graph() throws Exception {
		repository.updateRef(Constants.HEAD).link("refs/heads/main");
		git = Git.wrap(repository);
		for (String name : List.of("A", "B", "C", "D"))
			commit(name, "foo", name + "\n");
		git.checkout().setName("A").call();
		commit("E", "bar", "E\n");
		commit("F", "foo", "F\n");
		git.checkout().setName("B").call();
		git.merge().include(commits.get("E")).setMessage("merge E\n").call();
		git.tag().setName("merge-E").setAnnotated(false).call();
		for (String name : List.of("G", "H", "I"))
			commit(name, name, name + "\n");
		git.checkout().setName("main").call();
		args = repository.getDirectory().toPath().resolve("post-rewrite.args");
		data = repository.getDirectory().toPath().resolve("post-rewrite.data");
		hook(0, true);
	}

	private void hook(int exit, boolean executable) throws Exception {
		Path path = repository.getDirectory().toPath()
				.resolve("hooks/post-rewrite");
		Files.createDirectories(path.getParent());
		Files.writeString(path,
				"#!/bin/sh\nprintf '%s\\n' \"$@\" >>\"$GIT_DIR/post-rewrite.args\"\ncat >\"$GIT_DIR/post-rewrite.data\"\nexit "
						+ exit + "\n");
		assertTrue(FS.DETECTED.setExecute(path.toFile(), executable));
	}

	private void missing() {
		assertFalse(Files.exists(args));
		assertFalse(Files.exists(data));
	}

	private void mapping(String command, List<? extends ObjectId> oldIds,
			List<? extends ObjectId> newIds) throws Exception {
		assertEquals(command + "\n", Files.readString(args));
		StringBuilder expected = new StringBuilder();
		for (int i = 0; i < oldIds.size(); i++)
			expected.append(oldIds.get(i).name()).append(' ')
					.append(newIds.get(i).name()).append('\n');
		assertEquals(expected.toString(), Files.readString(data));
	}

	private void twoMappings() throws Exception {
		mapping("rebase", List.of(commits.get("C"), commits.get("D")), List
				.of(repository.resolve("HEAD^"), repository.resolve("HEAD")));
		assertEquals(RepositoryState.SAFE, repository.getRepositoryState());
	}

	private void resolve(String text) throws Exception {
		write("foo", text + "\n");
	}

	@Test
	public void amendSendsOldAndNewHeadsOnStdin() throws Exception {
		ObjectId old = repository.resolve(Constants.HEAD);
		RevCommit result = amend(false, false);
		mapping("amend", List.of(old), List.of(result));
	}

	@Test
	public void noPostRewriteSuppressesAmendNotification() throws Exception {
		amend(true, false);
		missing();
	}

	@Test
	public void ordinaryCommitDoesNotNotify() throws Exception {
		write("foo", "new\n");
		git.commit().setMessage("ordinary\n").call();
		missing();
	}

	@Test
	public void notificationFailureDoesNotUndoAmend() throws Exception {
		hook(7, true);
		ObjectId old = repository.resolve(Constants.HEAD);
		RevCommit result = amend(false, false);
		mapping("amend", List.of(old), List.of(result));
		assertEquals(result, repository.resolve(Constants.HEAD));
	}

	@Test
	public void nonExecutableHookDoesNotRun() throws Exception {
		hook(1, false);
		amend(false, false);
		missing();
	}

	@Test
	public void configuredRewriteHookReceivesStdin() throws Exception {
		Files.delete(repository.getDirectory().toPath()
				.resolve("hooks/post-rewrite"));
		repository.getConfig().setString("hook", "rewrite-check", "event",
				"post-rewrite");
		repository.getConfig().setString("hook", "rewrite-check", "command",
				"printf '%s\\n' \"$@\" >.git/post-rewrite.args; cat >.git/post-rewrite.data; :");
		ObjectId old = repository.resolve(Constants.HEAD);
		RevCommit result = amend(false, false);
		mapping("amend", List.of(old), List.of(result));
	}

	@Test
	public void successiveAmendsReportThePreviousHead() throws Exception {
		RevCommit first = amend(false, false);
		Files.delete(args);
		Files.delete(data);
		RevCommit second = amend(false, false);
		mapping("amend", List.of(first), List.of(second));
	}

	@Test
	public void postCommitRunsBeforeRewrite() throws Exception {
		Path postCommit = repository.getDirectory().toPath()
				.resolve("hooks/post-commit");
		Files.writeString(postCommit,
				"#!/bin/sh\necho post-commit >\"$GIT_DIR/order\"\n");
		assertTrue(FS.DETECTED.setExecute(postCommit.toFile(), true));
		Path rewrite = repository.getDirectory().toPath()
				.resolve("hooks/post-rewrite");
		Files.writeString(rewrite,
				"#!/bin/sh\ntest \"$(cat \"$GIT_DIR/order\")\" = post-commit || exit 5\necho post-rewrite >>\"$GIT_DIR/order\"\ncat >/dev/null\n");
		amend(false, false);
		assertEquals("post-commit\npost-rewrite\n", Files.readString(
				repository.getDirectory().toPath().resolve("order")));
	}

	@Test
	public void amendAllRetainsParentsAndReportsRewrite() throws Exception {
		Files.writeString(repository.getWorkTree().toPath().resolve("foo"),
				"unstaged\n");
		ObjectId old = repository.resolve(Constants.HEAD);
		RevCommit result = amend(false, true);
		mapping("amend", List.of(old), List.of(result));
		assertEquals(commits.get("C"), result.getParent(0));
		assertFalse(git.status().call().hasUncommittedChanges());
	}

	@Test
	public void continuedReplayReportsBothMappings() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.NORMAL).getStatus());
		missing();
		resolve("C");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		twoMappings();
	}

	@Test
	public void skipIntermediateCommitStillRecordsItsTarget() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.NORMAL).getStatus());
		assertEquals(RebaseResult.Status.STOPPED,
				resume(RebaseCommand.Operation.SKIP).getStatus());
		missing();
		resolve("D");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		twoMappings();
	}

	@Test
	public void skipLastCommitRecordsTheCurrentHead() throws Exception {
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef("F").call();
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("A", "D", Mode.NORMAL).getStatus());
		missing();
		assertTrue(resume(RebaseCommand.Operation.SKIP).getStatus()
				.isSuccessful());
		mapping("rebase", List.of(commits.get("E"), commits.get("F")), List
				.of(repository.resolve("HEAD"), repository.resolve("HEAD")));
	}

	@Test
	public void interactiveReplayReportsBothMappings() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.PICKS).getStatus());
		missing();
		resolve("C");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		twoMappings();
	}

	@Test
	public void interactiveDropDoesNotReportRemovedCommit() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.DROP).getStatus());
		missing();
		resolve("D");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		mapping("rebase", List.of(commits.get("D")),
				List.of(repository.resolve("HEAD")));
	}

	@Test
	public void interactiveSquashMapsBothCommitsToFinalHead() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.SQUASH).getStatus());
		missing();
		resolve("C");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		mapping("rebase", List.of(commits.get("C"), commits.get("D")), List
				.of(repository.resolve("HEAD"), repository.resolve("HEAD")));
	}

	@Test
	public void interactiveFixupMapsBothWithoutIntermediateAmendHook()
			throws Exception {
		assertTrue(rebase("B", null, Mode.FIXUP).getStatus().isSuccessful());
		mapping("rebase", List.of(commits.get("C"), commits.get("D")), List
				.of(repository.resolve("HEAD"), repository.resolve("HEAD")));
	}

	@Test
	public void interactiveRewordUsesOnlyFinalRebaseNotification()
			throws Exception {
		assertTrue(rebase("B", null, Mode.REWORD).getStatus().isSuccessful());
		twoMappings();
	}

	@Test
	public void abortedRebaseDoesNotNotify() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.NORMAL).getStatus());
		assertEquals(RebaseResult.Status.ABORTED,
				resume(RebaseCommand.Operation.ABORT).getStatus());
		missing();
		assertEquals(commits.get("D"), repository.resolve(Constants.HEAD));
	}

	@Test
	public void upToDateRebaseDoesNotNotify() throws Exception {
		assertEquals(RebaseResult.Status.UP_TO_DATE,
				rebase("C", null, Mode.NORMAL).getStatus());
		missing();
	}

	@Test
	public void failedRebaseNotificationDoesNotLeaveRebaseState()
			throws Exception {
		hook(7, true);
		assertTrue(rebase("B", null, Mode.FIXUP).getStatus().isSuccessful());
		mapping("rebase", List.of(commits.get("C"), commits.get("D")), List
				.of(repository.resolve("HEAD"), repository.resolve("HEAD")));
		assertEquals(RepositoryState.SAFE, repository.getRepositoryState());
	}

	private static void setAction(RebaseTodoLine step,
			RebaseTodoLine.Action action) {
		try {
			step.setAction(action);
		} catch (org.eclipse.jgit.errors.IllegalTodoFileModification e) {
			throw new AssertionError(e);
		}
	}

	/** C Git t7504: reword verifies the edited commit message. */
	@Test
	public void rewordInvokesCommitMessageHookWithoutAmendNotification() throws Exception {
		Path messageHook = repository.getDirectory().toPath().resolve("hooks/commit-msg");
		Files.writeString(messageHook, "#!/bin/sh\necho new message >\"$1\"\n");
		assertTrue(FS.DETECTED.setExecute(messageHook.toFile(), true));
		assertTrue(rebase("C", null, Mode.REWORD).getStatus().isSuccessful());
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
			assertEquals("new message\n",
					walk.parseCommit(repository.resolve(Constants.HEAD)).getFullMessage());
		}
		mapping("rebase", List.of(commits.get("D")), List.of(repository.resolve(Constants.HEAD)));
	}
}
