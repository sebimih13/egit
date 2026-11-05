/*******************************************************************************
 * Copyright (c) 2026 and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.test.op;

import java.io.IOException;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.op.PushOperation;
import org.eclipse.egit.core.op.PushOperationSpecification;
import org.eclipse.jgit.junit.PrePushHookTestCase;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;

/** C Git pre-push scenarios through the actual EGit PushOperation. */
public class PrePushHookOperationTest extends PrePushHookTestCase {
	@Override
	protected PushResult push(String remote, List<RefSpec> specifications)
			throws Exception {
		return executePush(remote, specifications, false);
	}

	@Override
	protected PushResult pushWithoutVerification(String remote,
			List<RefSpec> specifications) throws Exception {
		return executePush(remote, specifications, true);
	}

	private PushResult executePush(String remote, List<RefSpec> specifications,
			boolean noVerify) throws Exception {
		URIish uri = new URIish(location);
		PushOperation operation;
		if (remote == null || !remote.equals(location)) {
			if (!specifications.isEmpty()) {
				db.getConfig().setStringList("remote", remote, "push",
						specifications.stream().map(RefSpec::toString)
								.toList());
				db.getConfig().save();
			}
			operation = new PushOperation(db, remote, false, 0);
		} else {
			PushOperationSpecification specification = new PushOperationSpecification();
			try (Transport transport = Transport.open(db, uri)) {
				specification.addURIRefUpdates(uri,
						transport.findRemoteRefUpdatesFor(specifications));
			}
			operation = new PushOperation(db, specification, false, 0);
		}
		operation.setNoVerify(noVerify);
		operation.run(new NullProgressMonitor());
		uri = operation.getOperationResult().getURIs().iterator().next();
		PushResult result = operation.getOperationResult().getPushResult(uri);
		if (result == null) {
			throw new IOException(
					operation.getOperationResult().getErrorMessage(uri));
		}
		return result;
	}

	@org.junit.Test
	public void unsetPushDefaultPreservesCurrentBranchBehavior()
			throws Exception {
		installPrePush(0);
		push("parent1", List.of());
		org.junit.Assert.assertEquals(next, server.resolve("refs/heads/main"));
	}

}
