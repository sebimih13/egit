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
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonParser;

import org.eclipse.jgit.util.Trace2;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Opt-in cache regression suite for a fresh PDE process with GIT_TRACE2_EVENT
 * pointing to an absolute file. Run explicitly with -Dtest=Trace2IndexDiffCacheSuite.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({ IndexDiffCacheTest.class, IndexDiffCacheEntryTest.class })
public class Trace2IndexDiffCacheSuite {
	private static Path events;

	@BeforeClass
	public static void startTracing() {
		String target = System.getenv("GIT_TRACE2_EVENT"); //$NON-NLS-1$
		assertTrue("Set GIT_TRACE2_EVENT to an absolute output file", //$NON-NLS-1$
				target != null && Path.of(target).isAbsolute());
		events = Path.of(target);
		// Initialize before GitTestCase installs MockSystemReader, which hides
		// the real environment. This suite must run in its own PDE process.
		try (Trace2.Scope scope = Trace2.getInstance().region(null, "test", //$NON-NLS-1$
				"initialize")) { //$NON-NLS-1$
			assertTrue("Trace2 must be enabled for this suite", scope.isEnabled()); //$NON-NLS-1$
		}
	}

	@AfterClass
	public static void verifyTracing() throws Exception {
		Trace2.getInstance().close();
		String output = Files.readString(events);
		assertTrue(output.contains("\"event\":\"cmd_name\"")); //$NON-NLS-1$
		assertTrue(output.contains("\"name\":\"status\"")); //$NON-NLS-1$
		assertTrue(output.contains("\"value\":\"egit.indexdiff.full\"")); //$NON-NLS-1$
		assertTrue(output.contains("\"value\":\"egit.indexdiff.incremental\"")); //$NON-NLS-1$
		verifyWorkerRegions(output);
	}

	static void verifyWorkerRegions(String output) {
		String[] lines = output.strip().split("\n"); //$NON-NLS-1$
		// File targets append across PDE launches; inspect this session only.
		String sid = JsonParser.parseString(lines[lines.length - 1])
				.getAsJsonObject().get("sid").getAsString(); //$NON-NLS-1$
		Set<String> active = new HashSet<>();
		Set<String> seen = new HashSet<>();
		for (String line : lines) {
			var event = JsonParser.parseString(line).getAsJsonObject();
			if (!sid.equals(event.get("sid").getAsString())) { //$NON-NLS-1$
				continue;
			}
			String thread = event.get("thread").getAsString(); //$NON-NLS-1$
			switch (event.get("event").getAsString()) { //$NON-NLS-1$
			case "thread_start": //$NON-NLS-1$
				assertTrue("Worker identity must be unique", seen.add(thread)); //$NON-NLS-1$
				active.add(thread);
				break;
			case "region_enter": //$NON-NLS-1$
			case "region_leave": //$NON-NLS-1$
				assertTrue("Receiver needs thread_start before a worker region: " //$NON-NLS-1$
						+ thread, "main".equals(thread) || active.contains(thread)); //$NON-NLS-1$
				break;
			case "thread_exit": //$NON-NLS-1$
				assertTrue("Worker must be active", active.remove(thread)); //$NON-NLS-1$
				break;
			default:
				break;
			}
		}
		assertTrue("Cache tests must exercise background tracing", !seen.isEmpty()); //$NON-NLS-1$
		assertTrue("Worker contexts must close after jobs", active.isEmpty()); //$NON-NLS-1$
	}
}
