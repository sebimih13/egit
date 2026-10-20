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

import static org.junit.Assert.assertNotNull;
import java.util.List;
import org.eclipse.egit.core.op.PushOperation;
import org.eclipse.egit.core.op.PushOperationSpecification;
import org.eclipse.jgit.junit.PushToCheckoutTestCase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

/** C Git t5516 scenarios through EGit's actual PushOperation. */
public class PushToCheckoutPushOperationTest extends PushToCheckoutTestCase {
	private static java.nio.file.Path traceFile;

	private static int traceOffset;

	@org.junit.BeforeClass
	public static void initializeTrace() throws Exception {
		String target = System.getenv("GIT_TRACE2_EVENT");
		if (target != null && java.nio.file.Path.of(target).isAbsolute()) {
			org.junit.Assert.assertTrue(
					org.eclipse.jgit.util.Trace2.getInstance().isEnabled());
			traceFile = java.nio.file.Path.of(target);
			traceOffset = java.nio.file.Files.readAllLines(traceFile).size();
		}
	}

	@org.junit.AfterClass
	public static void verifyDeploymentHookEvents() throws Exception {
		if (traceFile == null) {
			return;
		}
		var lines = java.nio.file.Files.readAllLines(traceFile);
		var pending = new java.util.HashSet<String>();
		int starts = 0;
		int rejected = 0;
		for (String line : lines.subList(traceOffset, lines.size())) {
			var event = com.google.gson.JsonParser.parseString(line)
					.getAsJsonObject();
			String key = event.get("sid").getAsString() + ':'
					+ event.get("child_id");
			if ("child_start".equals(event.get("event").getAsString())
					&& event.has("hook_name") && "push-to-checkout"
							.equals(event.get("hook_name").getAsString())) {
				org.junit.Assert.assertEquals("hook",
						event.get("child_class").getAsString());
				org.junit.Assert.assertTrue(pending.add(key));
				starts++;
			} else if ("child_exit".equals(event.get("event").getAsString())
					&& pending.remove(key)) {
				org.junit.Assert.assertTrue(event.get("pid").getAsLong() > 0);
				org.junit.Assert
						.assertTrue(event.get("t_rel").getAsDouble() >= 0);
				if (event.get("code").getAsInt() != 0) {
					rejected++;
				}
			}
		}
		org.junit.Assert.assertEquals(9, starts);
		org.junit.Assert.assertEquals(3, rejected);
		org.junit.Assert.assertTrue(pending.isEmpty());
	}

	@Override
	protected PushResult doPush(Repository source, URIish uri,
			List<RemoteRefUpdate> updates) throws Exception {
		PushOperationSpecification specification = new PushOperationSpecification();
		specification.addURIRefUpdates(uri, updates);
		PushOperation operation = new PushOperation(source, specification,
				false, 0);
		operation.run(null);
		PushResult result = operation.getOperationResult().getPushResult(uri);
		assertNotNull(operation.getOperationResult().getErrorMessage(uri),
				result);
		return result;
	}
}
