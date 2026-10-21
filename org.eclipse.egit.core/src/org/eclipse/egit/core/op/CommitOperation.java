/*******************************************************************************
 * Copyright (c) 2010-2012, SAP AG and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Stefan Lay (SAP AG) - initial implementation
 *    Jens Baumgart (SAP AG)
 *    Robin Stocker (independent)
 *******************************************************************************/
package org.eclipse.egit.core.op;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.egit.core.Activator;
import org.eclipse.egit.core.RepositoryUtil;
import org.eclipse.egit.core.internal.CoreText;
import org.eclipse.egit.core.internal.job.RuleUtil;
import org.eclipse.egit.core.internal.signing.GpgConfigurationException;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.Trace2;
import org.eclipse.osgi.util.NLS;
import org.eclipse.team.core.TeamException;

/**
 * This class implements the commit of a list of files.
 */
public class CommitOperation implements IEGitOperation {

	private static final Pattern LEADING_WHITESPACE = Pattern
			.compile("^[\\h\\v]+"); //$NON-NLS-1$

	Collection<String> commitFileList;

	private boolean commitWorkingDirChanges = false;

	private final String author;

	private final String committer;

	private final String message;

	private boolean amending = false;

	private boolean noPostRewrite;

	private boolean noVerify;

	private Boolean allowEmpty;

	private Boolean allowEmptyMessage;

	private boolean useNativeMessageCleanup;

	private CleanupMode cleanupMode;

	private String originalMessage;

	private String hookPrefix = ""; //$NON-NLS-1$

	private boolean commitAll = false;

	private boolean sign = false;

	private Repository repo;

	Collection<String> notTracked;

	private boolean createChangeId;

	private boolean commitIndex;

	RevCommit commit = null;

	/**
	 * @param filesToCommit
	 *            a list of files which will be included in the commit
	 * @param notTracked
	 *            a list of all untracked files
	 * @param author
	 *            the author of the commit
	 * @param committer
	 *            the committer of the commit
	 * @param message
	 *            the commit message
	 * @throws CoreException
	 */
	public CommitOperation(IFile[] filesToCommit, Collection<IFile> notTracked,
			String author, String committer, String message) throws CoreException {
		this.author = author;
		this.committer = committer;
		this.originalMessage = message == null ? "" : message; //$NON-NLS-1$
		this.message = stripLeadingWhitespace(message);
		if (filesToCommit != null && filesToCommit.length > 0)
			setRepository(filesToCommit[0]);
		if (filesToCommit != null)
			commitFileList = buildFileList(Arrays.asList(filesToCommit));
		if (notTracked != null)
			this.notTracked = buildFileList(notTracked);
	}

	/**
	 * @param repository
	 * @param filesToCommit
	 *            a list of files which will be included in the commit
	 * @param notTracked
	 *            a list of all untracked files
	 * @param author
	 *            the author of the commit
	 * @param committer
	 *            the committer of the commit
	 * @param message
	 *            the commit message
	 * @throws CoreException
	 */
	public CommitOperation(Repository repository, Collection<String> filesToCommit, Collection<String> notTracked,
			String author, String committer, String message) throws CoreException {
		this.repo = repository;
		this.author = author;
		this.committer = committer;
		this.originalMessage = message == null ? "" : message; //$NON-NLS-1$
		this.message = stripLeadingWhitespace(message);
		if (filesToCommit != null)
			commitFileList = new HashSet<>(filesToCommit);
		if (notTracked != null)
			this.notTracked = new HashSet<>(notTracked);
	}

	/**
	 * Constructs a CommitOperation that commits the index
	 * @param repository
	 * @param author
	 * @param committer
	 * @param message
	 * @throws CoreException
	 */
	public CommitOperation(Repository repository, String author, String committer,
			String message) throws CoreException {
		this.repo = repository;
		this.author = author;
		this.committer = committer;
		this.originalMessage = message == null ? "" : message; //$NON-NLS-1$
		this.message = stripLeadingWhitespace(message);
		this.commitIndex = true;
	}

	private String stripLeadingWhitespace(String text) {
		return text == null ? "" //$NON-NLS-1$
				: LEADING_WHITESPACE.matcher(text).replaceFirst(""); //$NON-NLS-1$
	}

	private void setRepository(IFile file) throws CoreException {
		RepositoryMapping mapping = RepositoryMapping.getMapping(file);
		if (mapping == null)
			throw new CoreException(Activator.error(NLS.bind(
					CoreText.CommitOperation_couldNotFindRepositoryMapping,
					file), null));
		repo = mapping.getRepository();
	}

	/**
	 * @param repository
	 */
	public void setRepository(Repository repository) {
		repo = repository;
	}

	private Collection<String> buildFileList(Collection<IFile> files) throws CoreException {
		Collection<String> result = new HashSet<>();
		for (IFile file : files) {
			RepositoryMapping mapping = RepositoryMapping.getMapping(file);
			if (mapping == null)
				throw new CoreException(Activator.error(NLS.bind(CoreText.CommitOperation_couldNotFindRepositoryMapping, file), null));
			String repoRelativePath = mapping.getRepoRelativePath(file);
			result.add(repoRelativePath);
		}
		return result;
	}

	@Override
	public void execute(IProgressMonitor monitor) throws CoreException {
		try (Trace2.Scope trace = Trace2.getInstance().region(null, "egit", //$NON-NLS-1$
				"CommitOperation")) { //$NON-NLS-1$
			executeWithTrace(monitor);
		}
	}

	private void executeWithTrace(IProgressMonitor monitor)
			throws CoreException {
		IWorkspaceRunnable action = new IWorkspaceRunnable() {

			@Override
			public void run(IProgressMonitor actMonitor) throws CoreException {
				if (commitAll)
					commitAll();
				else if (amending || commitFileList != null
						&& commitFileList.size() > 0 || commitIndex) {
					SubMonitor progress = SubMonitor.convert(actMonitor);
					progress.setTaskName(
							CoreText.CommitOperation_PerformingCommit);
					addUntracked();
					commit();
				} else if (commitWorkingDirChanges) {
					// TODO commit -a
				} else {
					// TODO commit
				}
			}

		};
		ResourcesPlugin.getWorkspace().run(action, getSchedulingRule(),
				IWorkspace.AVOID_UPDATE, monitor);
	}

	private void addUntracked() throws CoreException {
		if (notTracked == null || notTracked.isEmpty()) {
			return;
		}
		try (Git git = new Git(repo)) {
			AddCommand addCommand = git.add();
			boolean fileAdded = false;
			for (String path : notTracked)
				if (commitFileList.contains(path)) {
					addCommand.addFilepattern(path);
					fileAdded = true;
				}
			if (fileAdded) {
				addCommand.call();
			}
		} catch (GitAPIException e) {
			throw new CoreException(Activator.error(e.getMessage(), e));
		}
	}

	@Override
	public ISchedulingRule getSchedulingRule() {
		return RuleUtil.getRule(repo);
	}

	private void commit() throws TeamException {
		try (Git git = new Git(repo)) {
			CommitCommand commitCommand = git.commit();
			setAuthorAndCommitter(commitCommand);
			commitCommand.setAmend(amending)
					.setNoPostRewrite(noPostRewrite)
					.setNoVerify(noVerify).setHookPrefix(hookPrefix)
					.setMessage(useNativeMessageCleanup ? originalMessage : message)
					.setInsertChangeId(createChangeId);
			if (!commitIndex)
				for(String path:commitFileList)
					commitCommand.setOnly(path);
			commit = commitCommand.call();
		} catch (GpgConfigurationException e) {
			throw new TeamException(e.getLocalizedMessage(), e);
		} catch (Exception e) {
			throw new TeamException(
					CoreText.MergeOperation_InternalError, e);
		}
	}

	/**
	 *
	 * @param amending
	 */
	public void setAmending(boolean amending) {
		this.amending = amending;
	}

	/**
	 * Suppress the post-rewrite notification for an amendment.
	 *
	 * @param noPostRewrite whether to suppress notification
	 * @since 7.8
	 */
	public void setNoPostRewrite(boolean noPostRewrite) {
		this.noPostRewrite = noPostRewrite;
	}

	/**
	 * Select native message-file normalization while retaining the supplied
	 * message for VERBATIM mode. Defaults to the existing operation behavior.
	 * @param nativeCleanup whether to normalize before/after message hooks
	 * @since 7.8
	 */
	public void setUseNativeMessageCleanup(boolean nativeCleanup) {
		useNativeMessageCleanup = nativeCleanup;
	}

	/**
	 * Set an explicit message cleanup policy; null retains the default.
	 * @param cleanup cleanup policy
	 * @since 7.8
	 */
	public void setCleanupMode(CleanupMode cleanup) {
		cleanupMode = cleanup;
	}

	/**
	 * Set an explicit empty-message policy, preserving the old default if unset.
	 * @param allow whether an empty final message is allowed
	 * @since 7.8
	 */
	public void setAllowEmptyMessage(boolean allow) {
		allowEmptyMessage = Boolean.valueOf(allow);
	}

	/**
	 * Select an explicit empty-commit policy. If unset, retain the operation's
	 * existing JGit default, which permits empty commits.
	 * @param allowEmpty whether an empty tree change is allowed
	 * @since 7.8
	 */
	public void setAllowEmpty(boolean allowEmpty) {
		this.allowEmpty = Boolean.valueOf(allowEmpty);
	}

	/**
	 * Set the invocation directory reported to commit hooks as GIT_PREFIX.
	 * @param prefix repository-relative directory including trailing slash;
	 *            null or empty for a root invocation
	 * @since 7.8
	 */
	public void setHookPrefix(String prefix) {
		hookPrefix = prefix == null ? "" : prefix; //$NON-NLS-1$
	}

	/**
	 * Bypass pre-commit and commit-msg verification. Message preparation and
	 * post-commit notification still run.
	 *
	 * @param noVerify whether to bypass verification hooks
	 * @since 7.8
	 */
	public void setNoVerify(boolean noVerify) {
		this.noVerify = noVerify;
	}

	/**
	 * @param sign
	 *            (see {@link CommitCommand#setSign(Boolean)}
	 */
	public void setSign(boolean sign) {
		this.sign = sign;
	}

	/**
	 *
	 * @param commitAll
	 */
	public void setCommitAll(boolean commitAll) {
		this.commitAll = commitAll;
	}

	/**
	 * @param createChangeId
	 *            <code>true</code> if a Change-Id should be inserted
	 */
	public void setComputeChangeId(boolean createChangeId) {
		this.createChangeId = createChangeId;
	}

	/**
	 * @return the newly created commit if committing was successful, null otherwise.
	 */
	public RevCommit getCommit() {
		return commit;
	}

	// TODO: can the commit message be change by the user in case of a merge commit?
	private void commitAll() throws TeamException {
		try (Git git = new Git(repo)) {
			CommitCommand commitCommand = git.commit();
			setAuthorAndCommitter(commitCommand);
			commit = commitCommand.setAll(true).setAmend(amending)
					.setNoPostRewrite(noPostRewrite)
					.setNoVerify(noVerify).setHookPrefix(hookPrefix).setMessage(useNativeMessageCleanup ? originalMessage : message)
					.setInsertChangeId(createChangeId).call();
		} catch (JGitInternalException e) {
			throw new TeamException(CoreText.MergeOperation_InternalError, e);
		} catch (GitAPIException e) {
			throw new TeamException(e.getLocalizedMessage(), e);
		}
	}

	private void setAuthorAndCommitter(CommitCommand commitCommand) throws TeamException {
		final Date commitDate = new Date();
		final TimeZone timeZone = TimeZone.getDefault();

		final PersonIdent enteredAuthor = RawParseUtils.parsePersonIdent(author);
		final PersonIdent enteredCommitter = RawParseUtils.parsePersonIdent(committer);
		if (enteredAuthor == null)
			throw new TeamException(NLS.bind(
					CoreText.CommitOperation_errorParsingPersonIdent, author));
		if (enteredCommitter == null)
			throw new TeamException(
					NLS.bind(CoreText.CommitOperation_errorParsingPersonIdent,
							committer));

		PersonIdent authorIdent;
		if (repo.getRepositoryState().equals(
				RepositoryState.CHERRY_PICKING_RESOLVED)) {
			try (RevWalk rw = new RevWalk(repo)) {
				ObjectId cherryPickHead = repo.readCherryPickHead();
				authorIdent = rw.parseCommit(cherryPickHead)
						.getAuthorIdent();
			} catch (IOException e) {
				ILog.of(getClass()).error(
						CoreText.CommitOperation_ParseCherryPickCommitFailed,
						e);
				throw new IllegalStateException(e);
			}
		} else {
			authorIdent = new PersonIdent(enteredAuthor, commitDate, timeZone);
		}

		final PersonIdent committerIdent = new PersonIdent(enteredCommitter, commitDate, timeZone);

		if (amending) {
			RevCommit headCommit = RepositoryUtil.INSTANCE
					.parseHeadCommit(repo);
			if (headCommit != null) {
				final PersonIdent headAuthor = headCommit.getAuthorIdent();
				authorIdent = new PersonIdent(enteredAuthor,
						headAuthor.getWhen(), headAuthor.getTimeZone());
			}
		}

		if (allowEmpty != null) {
			commitCommand.setAllowEmpty(allowEmpty.booleanValue());
		}

		if (cleanupMode != null) {
			commitCommand.setCleanupMode(cleanupMode);
		} else if (useNativeMessageCleanup) {
			commitCommand.setCleanupMode(CleanupMode.DEFAULT);
		}
		commitCommand.setUseNativeMessageCleanup(useNativeMessageCleanup);
		if (useNativeMessageCleanup) {
			commitCommand.setDefaultClean(false);
		}
		if (allowEmptyMessage != null) {
			commitCommand.setAllowEmptyMessage(allowEmptyMessage.booleanValue());
		}

		commitCommand.setAuthor(authorIdent);
		commitCommand.setCommitter(committerIdent);
		commitCommand.setSign(sign ? TRUE : FALSE);
		if (sign) {
			commitCommand.setGpgConfig(new EGitGpgConfig(repo.getConfig()));
		}
	}
}
