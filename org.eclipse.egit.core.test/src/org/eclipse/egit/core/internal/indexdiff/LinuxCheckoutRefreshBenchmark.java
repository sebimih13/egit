/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.egit.core.internal.indexdiff;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.egit.core.JobFamilies;
import org.eclipse.egit.core.RepositoryCache;
import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.egit.core.test.TestRepository;
import org.eclipse.egit.core.test.TestUtils;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

/** Opt-in full Linux checkout and actual Staging refresh measurement. */
public class LinuxCheckoutRefreshBenchmark {
	private static final Path PHASE = Path.of(
			"/home/seb/Developer/JEGit/artifacts/full-checkout-refresh-20261101");

	@Test
	public void measureCompleteCheckoutAndWorkspaceRefresh() throws Exception {
		Path request = PHASE.resolve("egit-benchmark.properties");
		assumeTrue("Explicit benchmark request required",
				Files.isRegularFile(request));
		Properties settings = new Properties();
		try (var in = Files.newInputStream(request)) {
			settings.load(in);
		}
		assumeTrue("Explicit benchmark enable required",
				Boolean.parseBoolean(settings.getProperty("enabled")));
		Path root = Path.of(settings.getProperty("repository")).toRealPath();
		assertEquals(PHASE.resolve("copies/linux-candidate").toRealPath(),
				root);
		String label = settings.getProperty("label");
		assertTrue(label.matches("[a-zA-Z0-9-]+"));
		Path results = PHASE.resolve(label + ".csv");
		assertFalse("Keep prior benchmark evidence", Files.exists(results));
		Properties outcome = new Properties();
		outcome.setProperty("valid", "false");
		outcome.setProperty("java.version", System.getProperty("java.version"));
		outcome.setProperty("java.vendor", System.getProperty("java.vendor"));
		outcome.setProperty("jgit.location",
				Repository.class.getProtectionDomain().getCodeSource()
						.getLocation().toString());
		outcome.setProperty("egit.location",
				BranchOperation.class.getProtectionDomain().getCodeSource()
						.getLocation().toString());
		try (BufferedWriter out = Files.newBufferedWriter(results)) {
			out.write("profile,round,head,branch_ns,refresh_ns,quiet_ns\n");
			for (boolean monitor : new boolean[] { false, true })
				runProfile(root, settings, monitor, out);
			outcome.setProperty("valid", "true");
		} finally {
			settings.setProperty("enabled", "false");
			try (var out = Files.newOutputStream(request)) {
				settings.store(out, "Consumed benchmark request");
			}
			try (var out = Files.newOutputStream(
					PHASE.resolve(label + "-result.properties"))) {
				outcome.store(out, "Complete EGit operation measurement");
			}
		}
	}

	private static void runProfile(Path root, Properties settings,
			boolean monitor, BufferedWriter output) throws Exception {
		String base = settings.getProperty("base");
		String parent = settings.getProperty("parent");
		Path gitDir = root.resolve(".git");
		Path clock = gitDir.resolve("egit-benchmark-clock");
		Files.writeString(clock, "start\n");
		StringBuilder script = new StringBuilder(
				"#!/bin/sh\nread clock < .git/egit-benchmark-clock\nprintf '%s\\000' \"$clock\"\nif test \"$2\" != \"$clock\"; then\n");
		int paths = Integer.parseInt(settings.getProperty("paths"));
		for (int i = 0; i < paths; i++) {
			String name = decode(settings.getProperty("path." + i));
			assertTrue(name.matches("[A-Za-z0-9_./+\\-]+"));
			script.append("printf '%s\\000' '").append(name).append("'\n");
		}
		script.append("fi\n");
		Path hook = gitDir.resolve("egit-benchmark-monitor");
		Files.writeString(hook, script);
		assertTrue(hook.toFile().setExecutable(true));
		nativeGit(root, settings, true, "config", "core.fsmonitor",
				monitor ? hook.toString() : "false");
		nativeGit(root, settings, true, "config", "core.fsmonitorHookVersion",
				"2");
		nativeGit(root, settings, true, "config", "jgit.fsmonitorCache",
				"true");
		nativeGit(root, settings, true, "checkout", "--quiet", "--detach",
				base);
		nativeGit(root, settings, true, "status", "--porcelain=v1", "-z",
				"--untracked-files=normal");
		nativeGit(root, settings, true, "update-index",
				monitor ? "--fsmonitor" : "--no-fsmonitor",
				"--force-write-index");
		Path description = root.resolve(".project");
		byte[] originalDescription = Files.readAllBytes(description);
		IProject project = null;
		Repository repository = null;
		TestRepository fixture = null;
		try {
			String projectName = "linux-checkout-benchmark-"
					+ System.nanoTime();
			Files.writeString(description,
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?><projectDescription><name>"
							+ projectName
							+ "</name><comment></comment><projects></projects><buildSpec></buildSpec><natures></natures></projectDescription>");
			var workspace = ResourcesPlugin.getWorkspace();
			project = workspace.getRoot().getProject(projectName);
			var desc = workspace.newProjectDescription(projectName);
			desc.setLocation(org.eclipse.core.runtime.Path
					.fromOSString(root.toString()));
			project.create(desc, null);
			project.open(null);
			repository = RepositoryCache.INSTANCE
					.lookupRepository(gitDir.toFile());
			assertEquals(root.toFile(), repository.getWorkTree());
			assertEquals(gitDir.toFile(), repository.getDirectory());
			repository.incrementOpen();
			repository.getConfig().load();
			fixture = new TestRepository(repository);
			fixture.connect(project);
			waitForJobs();
			IndexDiffCacheEntry entry = IndexDiffCache.INSTANCE
					.getIndexDiffCacheEntry(repository);
			assertNotNull(entry);
			refresh(entry);
			int warmups = Integer.parseInt(settings.getProperty("warmups"));
			int iterations = Integer
					.parseInt(settings.getProperty("iterations"));
			assertEquals(0, (warmups + iterations) % 2);
			for (int i = 0; i < warmups + iterations; i++) {
				String target = i % 2 == 0 ? parent : base;
				String prefix = i % 2 == 0 ? "parent" : "base";
				Files.writeString(clock, "clock-" + i + "\n");
				long start = System.nanoTime();
				BranchOperation operation = new BranchOperation(repository,
						target);
				operation.execute(null);
				long branch = System.nanoTime() - start;
				assertEquals(CheckoutResult.Status.OK,
						operation.getResult(repository).getStatus());
				start = System.nanoTime();
				refresh(entry);
				long refresh = System.nanoTime() - start;
				check(repository, entry.getIndexDiff(), root, settings, target,
						prefix);
				start = System.nanoTime();
				refresh(entry);
				long quiet = System.nanoTime() - start;
				check(repository, entry.getIndexDiff(), root, settings, target,
						prefix);
				output.write((monitor ? "controlled-hook" : "unmonitored") + ","
						+ (i - warmups) + "," + target + "," + branch + ","
						+ refresh + "," + quiet + "\n");
				output.flush();
			}
		} finally {
			waitForJobs();
			if (fixture != null && project != null && project.exists())
				fixture.disconnect(project);
			IndexDiffCache.INSTANCE.remove(gitDir.toFile());
			waitForJobs();
			if (fixture != null)
				fixture.dispose();
			else if (repository != null)
				repository.close();
			if (project != null && project.exists())
				project.delete(false, true, null);
			Files.write(description, originalDescription);
		}
	}

	private static void waitForJobs() throws Exception {
		TestUtils.waitForJobs(30000, JobFamilies.INDEX_DIFF_CACHE_UPDATE);
	}

	private static void refresh(IndexDiffCacheEntry entry) throws Exception {
		var job = entry.createRefreshResourcesAndIndexDiffJob();
		job.schedule();
		assertTrue("Staging refresh timeout", job.join(120000, null));
		assertTrue(job.getResult().toString(), job.getResult().isOK());
		waitForJobs();
	}

	private static void check(Repository repository, IndexDiffData data,
			Path root, Properties settings, String target, String prefix)
			throws Exception {
		assertEquals(target, repository.resolve("HEAD").name());
		assertEquals(Set.of("arch/alpha/Makefile"), data.getModified());
		assertEquals(Set.of(".project"), data.getIgnoredNotInIndex());
		assertTrue(data.getAdded().isEmpty());
		assertTrue(data.getChanged().isEmpty());
		assertTrue(data.getRemoved().isEmpty());
		assertTrue(data.getMissing().isEmpty());
		assertTrue(data.getUntracked().isEmpty());
		assertTrue(data.getConflicting().isEmpty());
		byte[] nativeStatus = nativeGit(root, settings, false, "-c",
				"core.fsmonitor=false", "-c", "core.untrackedCache=false",
				"status", "--porcelain=v1", "-z", "--untracked-files=all",
				"--ignored=matching", "--no-renames");
		assertArrayEquals(
				Base64.getDecoder()
						.decode(settings.getProperty(prefix + ".status")),
				nativeStatus);
		for (int i = 0; i < Integer
				.parseInt(settings.getProperty("paths")); i++) {
			Path file = root.resolve(decode(settings.getProperty("path." + i)));
			String type;
			byte[] bytes;
			if (Files.isSymbolicLink(file)) {
				type = "symlink";
				bytes = Files.readSymbolicLink(file).toString()
						.getBytes(StandardCharsets.UTF_8);
			} else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				type = Files.isExecutable(file) ? "executable" : "file";
				bytes = Files.readAllBytes(file);
			} else if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
				type = "directory";
				bytes = new byte[0];
			} else {
				type = "absent";
				bytes = new byte[0];
			}
			assertEquals(settings.getProperty(prefix + ".type." + i), type);
			assertEquals(settings.getProperty(prefix + ".sha256." + i),
					HexFormat.of().formatHex(MessageDigest
							.getInstance("SHA-256").digest(bytes)));
		}
	}

	private static String decode(String value) {
		return new String(Base64.getDecoder().decode(value),
				StandardCharsets.UTF_8);
	}

	private static byte[] nativeGit(Path root, Properties settings,
			boolean writable, String... args) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(settings.getProperty("git"));
		command.add("-C");
		command.add(root.toString());
		command.addAll(Arrays.asList(args));
		Path out = PHASE.resolve("egit-native-output.z");
		Path error = PHASE.resolve("egit-native-error.txt");
		ProcessBuilder builder = new ProcessBuilder(command)
				.redirectOutput(out.toFile()).redirectError(error.toFile());
		builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_OPTIONAL_LOCKS", writable ? "1" : "0");
		Process child = builder.start();
		try {
			assertTrue(child.waitFor(120, TimeUnit.SECONDS));
			assertEquals(Files.readString(error), 0, child.exitValue());
		} finally {
			child.destroyForcibly();
		}
		return Files.readAllBytes(out);
	}
}
