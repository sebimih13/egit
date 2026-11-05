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
import static org.junit.Assert.assertTrue;

import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.op.CommitOperation;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.Trace2;
import org.junit.Assume;
import org.junit.Test;

/**
 * Exercises the actual EGit commit operation, including inherited regressions.
 */
public class CommitOperationHookTrace2Test extends CommitOperationTest {
	private void writeHook(String name, int code) throws Exception {
		Path hook = repository.getDirectory().toPath().resolve("hooks")
				.resolve(name);
		Files.createDirectories(hook.getParent());
		Files.writeString(hook, "#!/bin/sh\nexit " + code + "\n");
		assertTrue(FS.DETECTED.setExecute(hook.toFile(), true));
	}

	private void commit() throws Exception {
		testUtils.addFileToProject(project.getProject(), "hook.txt", "content");
		new AddToIndexOperation(
				Collections.singleton(project.getProject().getFile("hook.txt")))
				.execute(null);
		CommitOperation operation = new CommitOperation(null, null,
				TestUtils.AUTHOR, TestUtils.COMMITTER, "hook fixture");
		operation.setCommitAll(true);
		operation.setRepository(repository);
		operation.execute(null);
	}

	private void verifyHookEvents(boolean rejected) throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		Trace2.getInstance();
		Field field = Trace2.class.getDeclaredField("initialized");
		field.setAccessible(true);
		Object previous = field.get(null);
		Constructor<Trace2> constructor = Trace2.class.getDeclaredConstructor(
				Writer.class, boolean.class, boolean.class, int.class,
				boolean.class, String.class);
		constructor.setAccessible(true);
		StringWriter events = new StringWriter();
		try (Trace2 trace = constructor.newInstance(events, false, true, 10,
				false, null)) {
			field.set(null, trace);
			writeHook("pre-commit", rejected ? 7 : 0);
			writeHook("commit-msg", 0);
			writeHook("post-commit", 0);
			try {
				commit();
				assertTrue("Hook should reject the commit", !rejected);
			} catch (CoreException failure) {
				if (!rejected) {
					throw failure;
				}
			}
			String text = events.toString();
			int count = rejected ? 1 : 3;
			assertEquals(count,
					text.split("\"event\":\"child_start\"", -1).length - 1);
			assertEquals(count,
					text.split("\"event\":\"child_exit\"", -1).length - 1);
			assertEquals(count,
					text.split("\"event\":\"region_leave\"", -1).length - 1);
			assertTrue(text.contains("\"child_class\":\"hook\""));
			assertTrue(text.contains("\"hook_name\":\"pre-commit\""));
			assertTrue(text.contains("\"code\":" + (rejected ? 7 : 0)));
			if (rejected) {
				assertEquals(null, repository.resolve("HEAD"));
			} else {
				assertTrue(text.contains("\"hook_name\":\"commit-msg\""));
				assertTrue(text.contains("\"hook_name\":\"post-commit\""));
			}
		} finally {
			field.set(null, previous);
		}
	}

	@Test
	public void commitHooksEmitTrace2() throws Exception {
		verifyHookEvents(false);
	}

	@Test
	public void rejectingHookEmitsExitAndStopsCommit() throws Exception {
		verifyHookEvents(true);
	}
}
