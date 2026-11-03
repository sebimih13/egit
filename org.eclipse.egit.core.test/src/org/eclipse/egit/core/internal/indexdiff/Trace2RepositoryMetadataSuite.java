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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import com.google.gson.JsonParser;

/** Fresh-PDE-JVM metadata regression, including the Staging View refresh job. */
@RunWith(Suite.class)
@Suite.SuiteClasses({ IndexDiffCacheTest.class, IndexDiffCacheEntryTest.class,
		FsMonitorIndexDiffCacheTest.class })
public class Trace2RepositoryMetadataSuite {
	@BeforeClass
	public static void startTracing() {
		Trace2IndexDiffCacheSuite.startTracing();
	}

	@AfterClass
	public static void verifyMetadata() throws Exception {
		// Only this opt-in suite's dedicated JVM owns the library session.
		Trace2IndexDiffCacheSuite.verifyTracing();
		var lines = Files.readAllLines(
				Path.of(System.getenv("GIT_TRACE2_EVENT"))); //$NON-NLS-1$
		String sid = JsonParser.parseString(lines.get(lines.size() - 1))
				.getAsJsonObject().get("sid").getAsString(); //$NON-NLS-1$
		Map<String, String> origins = new HashMap<>();
		Set<String> refreshes = new HashSet<>();
		Set<String> repositories = new HashSet<>();
		for (String line : lines) {
			var event = JsonParser.parseString(line).getAsJsonObject();
			if (!sid.equals(event.get("sid").getAsString()) //$NON-NLS-1$
					|| !event.has("category") || !event.has("key")) { //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			String scope = event.get("thread").getAsString() + '/' //$NON-NLS-1$
					+ event.get("nesting").getAsInt(); //$NON-NLS-1$
			if ("jgit".equals(event.get("category").getAsString())) { //$NON-NLS-1$ //$NON-NLS-2$
				if ("origin".equals(event.get("key").getAsString())) { //$NON-NLS-1$ //$NON-NLS-2$
					origins.put(scope, event.get("value").getAsString()); //$NON-NLS-1$
				} else if ("head".equals(event.get("key").getAsString())) { //$NON-NLS-1$ //$NON-NLS-2$
					var head = event.getAsJsonObject("value"); //$NON-NLS-1$
					assertTrue(head.has("branch")); //$NON-NLS-1$
					assertTrue(head.has("commit")); //$NON-NLS-1$
					assertTrue(head.get("commit").isJsonNull() //$NON-NLS-1$
							|| ObjectId.isId(head.get("commit").getAsString())); //$NON-NLS-1$
					String origin = origins.get(scope);
					if (origin != null && origin.startsWith("egit.indexdiff.")) { //$NON-NLS-1$
						refreshes.add(origin);
					}
				}
			} else if ("repository".equals(event.get("category").getAsString())) { //$NON-NLS-1$ //$NON-NLS-2$
				repositories.add(event.get("key").getAsString()); //$NON-NLS-1$
			}
		}
		assertTrue("Full refresh must record HEAD metadata", //$NON-NLS-1$
				refreshes.contains("egit.indexdiff.full")); //$NON-NLS-1$
		assertTrue("Incremental refresh must record HEAD metadata", //$NON-NLS-1$
				refreshes.contains("egit.indexdiff.incremental")); //$NON-NLS-1$
		assertTrue("Process snapshots must separate repositories", //$NON-NLS-1$
				repositories.size() > 1);
	}
}
