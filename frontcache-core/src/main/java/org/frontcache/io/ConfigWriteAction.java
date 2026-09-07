/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.frontcache.FCConfig;
import org.frontcache.FrontCacheEngine;
import org.frontcache.bots.BotClassifier;
import org.frontcache.resilience.FcResilienceReload;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code set-config} actually does, with no HttpServletRequest in sight.
 *
 * Separate from {@link FrontCacheIOServlet} so it can be tested for what matters here - the gates,
 * the allowlist, the normalization, the conflict check, the backup, the atomic replace - without
 * standing up a servlet container and a live {@code FrontCacheEngine}. The servlet keeps only the
 * parameter extraction.
 */
public class ConfigWriteAction {

	private static final Logger logger = LoggerFactory.getLogger(ConfigWriteAction.class);

	/**
	 * Property that turns runtime config writes off for this node.
	 *
	 * Not paranoia: a fleet whose conf/ is deployed from git, Ansible or a container image must not be
	 * editable from a web UI, because the next deployment silently reverts the edit and the drift is
	 * invisible until then. An operator in that position needs one property, not a policy.
	 */
	public static final String CONFIG_WRITE_PROPERTY = "front-cache.management.config-write";

	/** Applies a written file to the running node. A seam, so a test can write without a live engine. */
	interface Applier {
		/** @return warnings worth reporting; throws when the file was written but could not be applied */
		List<String> apply(ConfigFile configFile);
	}

	private ConfigWriteAction() { }

	/**
	 * Writes one config file and applies it.
	 *
	 * <h2>The order of the steps is the contract</h2>
	 *
	 * Gate, resolve, normalize, validate, compare, back up, write, apply. Everything that can refuse
	 * the write happens before anything is written, so a refusal never leaves a half-applied change -
	 * and validation runs before the conflict check so an operator with both a stale tab and a syntax
	 * error is told about both.
	 *
	 * @param authorizedToWrite the caller's verdict from {@code ApiKey.isAuthorizedToWrite} - passed in
	 *                          rather than read here, because this class has no request to read it from
	 */
	public static SetConfigActionResponse perform(String fileId, String content, String baseHash,
			boolean apply, boolean authorizedToWrite)
	{
		return perform(fileId, content, baseHash, apply, authorizedToWrite, LIVE_APPLIER);
	}

	static SetConfigActionResponse perform(String fileId, String content, String baseHash,
			boolean apply, boolean authorizedToWrite, Applier applier)
	{
		SetConfigActionResponse aResponse = new SetConfigActionResponse();

		// Stricter than the API-key check the servlet already ran: that one lets an un-keyed node
		// through. See ApiKey.isAuthorizedToWrite for why a write must not be what that state enables.
		if (!authorizedToWrite)
		{
			aResponse.refuse("config writes require a configured front-cache.api-key on this node"
					+ " - an un-keyed node is readable but not writable");
			return aResponse;
		}

		if (!Boolean.parseBoolean(FCConfig.getProperty(CONFIG_WRITE_PROPERTY, "true")))
		{
			aResponse.refuse("config writes are disabled on this node (" + CONFIG_WRITE_PROPERTY + "=false)");
			return aResponse;
		}

		ConfigFile configFile = ConfigFile.byId(fileId);
		if (null == configFile)
		{
			aResponse.refuse("unknown 'file' - expected one of " + ConfigFile.ids());
			return aResponse;
		}
		aResponse.setFile(configFile.getFileName());

		if (null == content)
		{
			aResponse.refuse("'content' is required - the whole file, not a patch");
			return aResponse;
		}
		String normalized = ConfigFileStore.normalize(content);

		File target = FCConfig.getWritableConfigFile(configFile.getFileName());
		if (null == target)
		{
			// Not a fallback situation: FCConfig.getConfigInputStream reads the classpath too, so the
			// file the console is DISPLAYING may not be a file on disk at all. There is then nothing to
			// write, and inventing a location is worse than saying so.
			aResponse.refuse("this node has no FRONTCACHE_HOME/conf directory on disk, so there is no"
					+ " file to write - its configuration comes from the classpath");
			return aResponse;
		}

		List<String> validationErrors = ConfigFileValidator.validate(configFile, normalized);
		if (!validationErrors.isEmpty())
		{
			aResponse.setValidationErrors(validationErrors);
			aResponse.refuse(configFile.getFileName() + " was NOT written - " + validationErrors.size()
					+ " line(s) cannot be used");
			return aResponse;
		}

		try {
			String onDisk = ConfigFileStore.readOnDisk(target);

			// Optimistic concurrency. An absent baseHash means the caller could not compute one (see
			// fc-config-editor.js: crypto.subtle is undefined on a plain-http non-loopback page), so the
			// check is skipped rather than the save refused - and the response says which happened.
			if (null != baseHash && 0 < baseHash.trim().length())
			{
				if (!baseHash.trim().equalsIgnoreCase(ConfigFileStore.hash(onDisk)))
				{
					aResponse.setCurrentContent(onDisk);
					aResponse.refuse(configFile.getFileName() + " changed on this node since it was read"
							+ " - nothing was written. The current content is in 'currentContent'.");
					return aResponse;
				}
			} else {
				aResponse.addWarning("saved without a concurrency check - no 'baseHash' was sent, so a"
						+ " change made on this node since the file was read would have been overwritten");
			}

			boolean created = !target.exists();

			if (normalized.equals(onDisk))
			{
				// Nothing to write, but the apply below still runs: "save with no edit" is how an
				// operator re-applies a file they edited on the box by hand, and refusing it here would
				// make that a restart.
				aResponse.addWarning("the content is identical to what is on disk - nothing was written");
			} else {
				aResponse.setBackupFile(ConfigFileStore.backup(target));
				ConfigFileStore.write(target, normalized);
				aResponse.setWritten(true);
			}

			if (created)
				aResponse.addWarning("conf/" + configFile.getFileName() + " did not exist and was created"
						+ " - if this node was using a bundled default of the same name, this file now"
						+ " shadows it");

			logger.info("set-config wrote conf/" + configFile.getFileName() + " ("
					+ normalized.getBytes(StandardCharsets.UTF_8).length + " bytes, written="
					+ aResponse.isWritten() + ", backup=" + aResponse.getBackupFile() + ")");

		} catch (Exception e) {
			logger.error("Can't write conf/" + configFile.getFileName(), e);
			aResponse.refuse("can't write conf/" + configFile.getFileName() + " - " + e.getMessage());
			return aResponse;
		}

		if (!apply)
		{
			aResponse.addWarning("written but NOT applied (apply=false) - the running node still has the"
					+ " previous configuration until it is reloaded");
			return aResponse;
		}

		try {
			aResponse.getWarnings().addAll(applier.apply(configFile));
			aResponse.setApplied(true);

		} catch (Exception e) {
			logger.error("conf/" + configFile.getFileName() + " was written but could not be applied", e);
			// NOT a refusal: the file IS written, and saying otherwise would send the operator looking
			// for a change that is on disk. The status stays OK with applied=false, which is a state the
			// response models on purpose.
			aResponse.addWarning("written, but applying it failed (" + e.getMessage()
					+ ") - the running node still has the previous configuration");
		}

		return aResponse;
	}

	/**
	 * Reloads whichever subsystem owns the file just written.
	 *
	 * Deliberately calling the same code the standalone reload actions call, rather than repeating it:
	 * two ways to apply one file is how the apply-on-save path and the reload action drift apart, and
	 * the drift would only show up as "the file says X and the node does Y".
	 */
	static final Applier LIVE_APPLIER = new Applier() {

		@Override
		public List<String> apply(ConfigFile configFile) {

			List<String> warnings = new java.util.ArrayList<String>();

			switch (configFile)
			{
			case BOTS:
				BotClassifier.reload();
				break;

			case GUARD_RULES:
				FrontCacheEngine.getFrontCache().getGuardRuleEngine().reload();
				break;

			case FALLBACKS:
				FallbackResolverFactory.destroy();
				FallbackResolverFactory.init(FrontCacheEngine.getFrontCache().getFcHttpClient());
				break;

			case DYNAMIC_URLS:
				warnings.add(FCConfig.reloadDynamicURLs() + " dynamic URL pattern(s) are now active");
				break;

			case RESILIENCE:
				warnings.addAll(FcResilienceReload.reload().warnings());
				break;
			}

			return warnings;
		}
	};
}
