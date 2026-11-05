/*******************************************************************************
 * Copyright (C) 2011, Stefan Lay <stefan.lay@sap.com>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.op;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.egit.core.Activator;
import org.eclipse.egit.core.op.CloneOperation.PostCloneTask;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.Trace2;

/**
 * Configures the push specification of the cloned repository
 */
public class ConfigurePushAfterCloneTask implements PostCloneTask {

	private String pushRefSpec;

	private URIish pushURI;

	private final String remoteName;

	/**
	 * @param remoteName
	 * @param pushRefSpec
	 * @param pushURI
	 */
	public ConfigurePushAfterCloneTask(String remoteName, String pushRefSpec, URIish pushURI) {
		this.remoteName = remoteName;
		this.pushRefSpec = pushRefSpec;
		this.pushURI = pushURI;
	}

	/**
	 * @param repository
	 * @param monitor
	 * @throws CoreException
	 */
	@Override
	public void execute(Repository repository, IProgressMonitor monitor)
			throws CoreException {
		try (Trace2.Scope trace = Trace2.getInstance().region(null, "egit", //$NON-NLS-1$
				"ConfigurePushAfterCloneTask")) { //$NON-NLS-1$
			executeWithTrace(repository, monitor);
		}
	}

	private void executeWithTrace(Repository repository, IProgressMonitor monitor)
			throws CoreException {
		try {
			RemoteConfig configToUse = new RemoteConfig(
					repository.getConfig(), remoteName);
			if (pushRefSpec != null)
				configToUse.addPushRefSpec(new RefSpec(pushRefSpec));
			if (pushURI != null)
				configToUse.addPushURI(pushURI);
			configToUse.update(repository.getConfig());
			repository.getConfig().save();
		} catch (Exception e) {
			throw new CoreException(Activator.error(e.getMessage(), e));
		}

	}

}
