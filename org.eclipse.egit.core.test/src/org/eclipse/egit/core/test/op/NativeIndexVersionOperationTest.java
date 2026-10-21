/*******************************************************************************
 * Copyright (c) 2026 and others.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.test.op;

import java.io.File;
import java.util.List;
import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.op.AddToIndexOperation;
import org.eclipse.egit.core.test.GitTestCase;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.jgit.junit.MockSystemReader;
import org.eclipse.jgit.junit.NativeIndexVersionSupport;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Native t1600 precedence through the actual EGit staging operation. */
@RunWith(Parameterized.class)
public class NativeIndexVersionOperationTest extends GitTestCase {
	@Parameterized.Parameters(name = "config={0}, manyFiles={1}, env={2}, expected={3}")
	public static List<Object[]> scenarios() { return NativeIndexVersionSupport.scenarios(); }
	private final int configuredVersion;
	private final boolean manyFiles;
	private final String environmentVersion;
	private final int expectedVersion;

	public NativeIndexVersionOperationTest(int configuredVersion, boolean manyFiles,
			String environmentVersion, int expectedVersion) {
		this.configuredVersion = configuredVersion;
		this.manyFiles = manyFiles;
		this.environmentVersion = environmentVersion;
		this.expectedVersion = expectedVersion;
	}

	@Test
	public void nativeIndexVersionPrecedence() throws Exception {
		TestRepository fixture = new TestRepository(new File(
				project.getProject().getLocationURI().getPath(), Constants.DOT_GIT));
		try {
			fixture.connect(project.getProject());
			var repository = fixture.getRepository();
			NativeIndexVersionSupport.configure(repository, configuredVersion, manyFiles);
			var file = testUtils.addFileToProject(project.getProject(), "a", "1\n");
			var nativeIndex = NativeIndexVersionSupport.nativeIndex(repository,
					environmentVersion, expectedVersion, manyFiles);
			((MockSystemReader) SystemReader.getInstance()).setProperty("GIT_INDEX_VERSION",
					environmentVersion);
			new AddToIndexOperation(new IResource[] { file }).execute(null);
			NativeIndexVersionSupport.assertMatches(repository, nativeIndex, expectedVersion, manyFiles);
			repository.getConfig().setInt("index", null, "version", 1);
			repository.getConfig().save();
			((MockSystemReader) SystemReader.getInstance()).setProperty("GIT_INDEX_VERSION", "1");
			file.setContents(new java.io.ByteArrayInputStream(
					"restaged content\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
					true, false, null);
			NativeIndexVersionSupport.nativeRestage(repository, expectedVersion, manyFiles);
			new AddToIndexOperation(new IResource[] { file }).execute(null);
			NativeIndexVersionSupport.assertMatches(repository, nativeIndex, expectedVersion, manyFiles);
		} finally {
			fixture.dispose();
		}
	}
}
