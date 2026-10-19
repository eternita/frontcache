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
package org.frontcache.license;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The version and release date this jar was built as - read from {@code org/frontcache/build-info.properties},
 * which the build generates from {@code version.properties} (see {@code generateBuildInfo} in build.gradle).
 *
 * <h2>Why a release date at all</h2>
 *
 * It is the half of the perpetual-fallback check that the customer cannot move: a license is valid for a
 * build when the build was released on or before the license's {@code updates-until}. Both sides of that
 * comparison are fixed at signing / build time, so setting the clock back changes nothing - see
 * docs/ip-protection-proposal.md section 5.3.
 *
 * The date is {@code release-date} from version.properties when it is set (the release cut sets it), and
 * the build day otherwise. A development build therefore carries "today", which is the honest answer: it
 * was released, to whoever built it, today.
 *
 * <h2>Unknown is a value</h2>
 *
 * Classes compiled by an IDE without running the Gradle resource step have no build-info at all. That
 * yields {@link #UNKNOWN_VERSION} and a null release date, and the license check skips the
 * {@code updates-until} comparison rather than failing it - a missing resource must not read as an
 * expired subscription.
 */
public final class BuildInfo {

	private static final Logger logger = LoggerFactory.getLogger(BuildInfo.class);

	static final String RESOURCE = "/org/frontcache/build-info.properties";

	public static final String UNKNOWN_VERSION = "unknown";

	private static final BuildInfo CURRENT = load();

	private final String version;
	private final LocalDate releaseDate;

	BuildInfo(String version, LocalDate releaseDate) {
		this.version = (null == version || version.isBlank()) ? UNKNOWN_VERSION : version.trim();
		this.releaseDate = releaseDate;
	}

	public static BuildInfo current() {
		return CURRENT;
	}

	/** The Frontcache version, or {@link #UNKNOWN_VERSION}. */
	public String getVersion() {
		return version;
	}

	/** The day this build was released, or null when the jar carries no build-info. */
	public LocalDate getReleaseDate() {
		return releaseDate;
	}

	private static BuildInfo load() {

		try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE))
		{
			if (null == in)
			{
				logger.info("No " + RESOURCE + " on the classpath - version and release date are unknown"
						+ " (an IDE build that skipped the Gradle resource step?)");
				return new BuildInfo(null, null);
			}

			Properties props = new Properties();
			props.load(in);

			return new BuildInfo(props.getProperty("version"), parseDate(props.getProperty("release-date")));

		} catch (Exception e) {
			logger.warn("Can't read " + RESOURCE + " - version and release date are unknown", e);
			return new BuildInfo(null, null);
		}
	}

	private static LocalDate parseDate(String value) {

		if (null == value || value.isBlank())
			return null;

		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			logger.warn("build-info release-date '" + value + "' is not yyyy-MM-dd - treated as unknown");
			return null;
		}
	}

	@Override
	public String toString() {
		return version + " (released " + (null == releaseDate ? "unknown" : releaseDate.toString()) + ")";
	}
}
