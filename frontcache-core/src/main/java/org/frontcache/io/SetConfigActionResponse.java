/*
 * Copyright 2015 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.io;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code set-config} did, or refused to do.
 *
 * <h2>{@code written} and {@code applied} are separate on purpose</h2>
 *
 * A file can be written and not applied ({@code apply=false}, or a reload that reported a problem),
 * and a refusal writes nothing at all. Collapsing the two into one status would leave the console
 * saying "saved" for a node whose running configuration did not change, which is the whole thing the
 * Configs screens exist to make visible.
 *
 * <h2>{@code validationErrors} versus {@code warnings}</h2>
 *
 * An error means nothing was written - the file was dry-parsed first and did not survive it, and the
 * message carries the line number. A warning means the write happened and there is something the
 * operator has to know anyway: a file that had to be created, a resilience setting that needs a
 * restart, the circuit state a reload reset. Both are lists because one bad paste produces several.
 *
 * <h2>{@code currentContent} is only filled on a conflict</h2>
 *
 * When {@code baseHash} does not match what is on disk, someone else changed the file since the
 * console rendered it. Returning the current content with the refusal is what lets the console show
 * the operator what they were about to overwrite, instead of only telling them they could not.
 */
public class SetConfigActionResponse extends ActionResponse {

	/** The file's name on disk, e.g. {@code bots.conf} - not the logical id the caller sent. */
	private String file;

	private boolean written = false;

	private boolean applied = false;

	/** e.g. {@code bots.conf.backup-20260907_141233}, or null when there was no file to back up. */
	private String backupFile;

	private List<String> validationErrors = new ArrayList<String>();

	private List<String> warnings = new ArrayList<String>();

	/** Filled only on a baseHash conflict - see the class comment. */
	private String currentContent;

	public SetConfigActionResponse() { // for JSON mapper
		setAction(FrontcacheAction.SET_CONFIG);
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	/** Marks this response a refusal: ERROR status, nothing written. */
	public void refuse(String reason)
	{
		setResponseStatus(RESPONSE_STATUS_ERROR);
		setErrorDescription(reason);
		this.written = false;
		this.applied = false;
	}

	public String getFile() {
		return file;
	}

	public void setFile(String file) {
		this.file = file;
	}

	public boolean isWritten() {
		return written;
	}

	public void setWritten(boolean written) {
		this.written = written;
	}

	public boolean isApplied() {
		return applied;
	}

	public void setApplied(boolean applied) {
		this.applied = applied;
	}

	public String getBackupFile() {
		return backupFile;
	}

	public void setBackupFile(String backupFile) {
		this.backupFile = backupFile;
	}

	public List<String> getValidationErrors() {
		return validationErrors;
	}

	public void setValidationErrors(List<String> validationErrors) {
		this.validationErrors = (null == validationErrors) ? new ArrayList<String>() : validationErrors;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public void setWarnings(List<String> warnings) {
		this.warnings = (null == warnings) ? new ArrayList<String>() : warnings;
	}

	public void addWarning(String warning) {
		this.warnings.add(warning);
	}

	public String getCurrentContent() {
		return currentContent;
	}

	public void setCurrentContent(String currentContent) {
		this.currentContent = currentContent;
	}

}
