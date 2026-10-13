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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.op.CommitOperation;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.Trace2;
import org.junit.Assume;
import org.junit.Test;

public class PrepareCommitMsgOperationTest extends CommitOperationTest {
	private void execute(boolean reject, boolean amend) throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		testUtils.addFileToProject(project.getProject(), "prepare.txt",
				"content");
		new AddToIndexOperation(Collections
				.singleton(project.getProject().getFile("prepare.txt")))
				.execute(null);
		if (amend) {
			CommitOperation initial = new CommitOperation(null, null,
					TestUtils.AUTHOR, TestUtils.COMMITTER, "initial");
			initial.setRepository(repository);
			initial.setCommitAll(true);
			initial.execute(null);
		}
		var oldHead = repository.resolve("HEAD");
		Path hook = repository.getDirectory().toPath()
				.resolve("hooks/prepare-commit-msg");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook,
				"#!/bin/sh\ntest \"$#\" = 2 && test \"$2\" = message && test \"$GIT_EDITOR\" = : || exit 9\nprintf 'prepared message\\n' > \"$1\"\nexit "
						+ (reject ? 7 : 0) + "\n");
		assertTrue(FS.DETECTED.setExecute(hook.toFile(), true));
		Trace2.getInstance();
		Field initialized = Trace2.class.getDeclaredField("initialized");
		initialized.setAccessible(true);
		Object previous = initialized.get(null);
		Constructor<Trace2> constructor = Trace2.class.getDeclaredConstructor(
				Writer.class, boolean.class, boolean.class, int.class,
				boolean.class, String.class);
		constructor.setAccessible(true);
		StringWriter events = new StringWriter();
		try (Trace2 trace = constructor.newInstance(events, false, true, 10,
				false, null)) {
			initialized.set(null, trace);
			CommitOperation operation = new CommitOperation(null, null,
					TestUtils.AUTHOR, TestUtils.COMMITTER, "original");
			operation.setRepository(repository);
			operation.setCommitAll(true);
			operation.setAmending(amend);
			try {
				operation.execute(null);
				assertFalse("Preparation must abort", reject);
				assertEquals("prepared message\n",
						operation.getCommit().getFullMessage());
				assertEquals(operation.getCommit(), repository.resolve("HEAD"));
			} catch (CoreException failure) {
				if (!reject) {
					throw failure;
				}
				assertEquals(oldHead, repository.resolve("HEAD"));
				assertNull(operation.getCommit());
			}
			List<JsonObject> records = events.toString().lines()
					.map(line -> JsonParser.parseString(line).getAsJsonObject())
					.filter(event -> event.has("child_id")
							|| event.has("category") && event.get("category")
									.getAsString().equals("hook"))
					.toList();
			assertEquals(4, records.size());
			assertEquals("region_enter",
					records.get(0).get("event").getAsString());
			assertEquals("prepare-commit-msg",
					records.get(0).get("label").getAsString());
			assertEquals("child_start",
					records.get(1).get("event").getAsString());
			assertEquals("hook",
					records.get(1).get("child_class").getAsString());
			assertEquals("prepare-commit-msg",
					records.get(1).get("hook_name").getAsString());
			assertEquals("child_exit",
					records.get(2).get("event").getAsString());
			assertEquals(records.get(1).get("child_id"),
					records.get(2).get("child_id"));
			assertEquals(reject ? 7 : 0, records.get(2).get("code").getAsInt());
			assertTrue(records.get(2).get("pid").getAsLong() > 0);
			assertTrue(records.get(2).get("t_rel").getAsDouble() >= 0);
			assertEquals("region_leave",
					records.get(3).get("event").getAsString());
			assertEquals(records.get(0).get("label"),
					records.get(3).get("label"));
		} finally {
			initialized.set(null, previous);
		}
	}

	@Test
	public void preparationEditsCommittedMessage() throws Exception {
		execute(false, false);
	}

	@Test
	public void preparationRejectsCommitAndPreservesHead() throws Exception {
		execute(true, false);
	}

	@Test
	public void preparationRunsForAmend() throws Exception {
		execute(false, true);
	}
}
