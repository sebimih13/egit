/*******************************************************************************
 * Copyright (C) 2026, and others.
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.op.CommitOperation;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.Trace2;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class ConfiguredHookOperationTest extends CommitOperationTest {
	private static Path output;

	private static int firstLine;

	@BeforeClass
	public static void initializeRealTracingBeforeMockSystemReader()
			throws Exception {
		String target = System.getenv("GIT_TRACE2_EVENT");
		if (target != null && Path.of(target).isAbsolute()) {
			assertTrue(Trace2.getInstance().isEnabled());
			output = Path.of(target);
			firstLine = Files.readAllLines(output).size();
		}
	}

	@AfterClass
	public static void verifyRealCommandHookContext() throws Exception {
		if (output == null)
			return; // Ordinary non-tracing suite still tests hook behavior.
		List<String> lines = Files.readAllLines(output);
		Map<String, List<JsonObject>> regions = new HashMap<>();
		Map<String, JsonObject> children = new HashMap<>();
		int starts = 0;
		for (String line : lines.subList(firstLine, lines.size())) {
			JsonObject event = JsonParser.parseString(line).getAsJsonObject();
			String key = event.get("sid").getAsString() + '/'
					+ event.get("thread").getAsString();
			List<JsonObject> stack = regions.computeIfAbsent(key,
					value -> new ArrayList<>());
			switch (event.get("event").getAsString()) {
			case "region_enter":
				stack.add(event);
				break;
			case "region_leave":
				if (!stack.isEmpty())
					stack.remove(stack.size() - 1);
				break;
			case "child_start":
				if (!event.get("child_class").getAsString().equals("hook"))
					break;
				assertFalse(stack.isEmpty());
				assertEquals("hook", stack.get(stack.size() - 1).get("category")
						.getAsString());
				assertEquals(event.get("hook_name"),
						stack.get(stack.size() - 1).get("label"));
				assertTrue(stack.stream().anyMatch(region -> region
						.get("category").getAsString().equals("jgit")
						&& region.get("label").getAsString().equals("commit")));
				assertNull(
						children.put(key + ':' + event.get("child_id"), event));
				starts++;
				break;
			case "child_exit":
				JsonObject start = children
						.remove(key + ':' + event.get("child_id"));
				if (start != null) {
					assertTrue(event.get("pid").getAsLong() > 0);
					assertTrue(event.get("t_rel").getAsDouble() >= 0);
				}
				break;
			default:
				break;
			}
		}
		assertEquals(4, starts);
		assertTrue(children.isEmpty());
	}

	private CommitOperation operation() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		testUtils.addFileToProject(project.getProject(), "configured.txt",
				"content");
		new AddToIndexOperation(Collections
				.singleton(project.getProject().getFile("configured.txt")))
				.execute(null);
		CommitOperation operation = new CommitOperation(null, null,
				TestUtils.AUTHOR, TestUtils.COMMITTER, "original");
		operation.setCommitAll(true);
		operation.setRepository(repository);
		return operation;
	}

	private void named(String name, String event, String command) {
		repository.getConfig().setString("hook", name, "command", command);
		repository.getConfig().setString("hook", name, "event", event);
	}

	@Test
	public void orderedConfiguredPreparationsModifyActualCommit()
			throws Exception {
		named("one", "prepare-commit-msg", "printf 'first\\n' > \"$1\"");
		named("two", "prepare-commit-msg",
				"test \"$(cat \"$1\")\" = first || exit 9; printf 'second\\n' > \"$1\"; :");
		CommitOperation operation = operation();
		operation.execute(null);
		assertEquals("second\n", operation.getCommit().getFullMessage());
	}

	@Test
	public void configuredRejectionStillRunsNextHookAndLeavesHeadUnchanged()
			throws Exception {
		named("one", "pre-commit", "exit 1");
		named("two", "pre-commit", "echo done > .git/configured-log; exit 2");
		CommitOperation operation = operation();
		assertThrows(CoreException.class, () -> operation.execute(null));
		assertNull(repository.resolve("HEAD"));
		assertEquals("done\n", Files.readString(
				repository.getDirectory().toPath().resolve("configured-log")));
	}

	@Test
	public void disabledConfiguredHooksDoNotRejectCommit() throws Exception {
		named("one", "pre-commit", "exit 7");
		repository.getConfig().setBoolean("hook", "one", "enabled", false);
		CommitOperation operation = operation();
		operation.execute(null);
		assertNotNull(operation.getCommit());
	}
}
