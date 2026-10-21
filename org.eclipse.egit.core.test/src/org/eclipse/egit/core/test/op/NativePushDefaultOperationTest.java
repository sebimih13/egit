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
import org.eclipse.jgit.junit.PushDefaultTestCase;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** C Git t5528 scenarios through the actual EGit configured push operation. */
@RunWith(Parameterized.class)
public class NativePushDefaultOperationTest extends PushDefaultTestCase {
	@Parameterized.Parameters(name = "{0}")
	public static List<Object[]> cases() {
		return scenarios();
	}

	public NativePushDefaultOperationTest(String scenario) {
		super(scenario);
	}

	@Override
	protected boolean ignoresInvalidRebasePolicy() {
		return true;
	}

	@Override
	protected boolean executePush(String remote, List<RefSpec> specs,
			boolean dryRun) throws Exception {
		PushOperation operation;
		if (!specs.isEmpty()) {
			URIish uri = new URIish(
					db.getConfig().getString("remote", remote, "url"));
			PushOperationSpecification specification = new PushOperationSpecification();
			try (Transport transport = Transport.open(db, uri)) {
				specification.addURIRefUpdates(uri,
						transport.findRemoteRefUpdatesFor(specs));
			}
			operation = new PushOperation(db, specification, dryRun, 0);
		} else {
			operation = new PushOperation(db, remote, dryRun, 0);
		}
		operation.setPushDefault(null);
		java.util.concurrent.atomic.AtomicBoolean warned = new java.util.concurrent.atomic.AtomicBoolean();
		org.eclipse.core.runtime.ILogListener listener = (status, plugin) -> {
			if (status.getMessage().contains("branch.autoSetupRebase"))
				warned.set(true);
		};
		org.eclipse.core.runtime.ILog log = org.eclipse.core.runtime.ILog.of(
				org.eclipse.egit.core.internal.ReportingTypedConfigGetter.class);
		log.addLogListener(listener);
		try {
			operation.run(new NullProgressMonitor());
		} finally {
			log.removeLogListener(listener);
		}
		if (scenario.equals("auto-rebase-invalid")) {
			org.junit.Assert.assertTrue("Invalid IDE policy must be reported",
					warned.get());
		}
		var result = operation.getOperationResult();
		if (result.getURIs().isEmpty())
			throw new IOException(
					"Push operation did not report a destination for "
							+ remote);
		var uri = result.getURIs().iterator().next();
		if (!result.isSuccessfulConnection(uri))
			throw new IOException(result.getErrorMessage(uri));
		PushResult push = result.getPushResult(uri);
		if (push == null)
			throw new IOException(result.getErrorMessage(uri));
		return push.getRemoteUpdates().stream().allMatch(update -> update
				.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
				|| update
						.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE);
	}
}
