/*
 * Copyright 2018 Eternita
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One plugin as {@code get-node-stats} reports it: what it is (from its manifest), its state, and its counters.
 * The counters are the process's - a reload does not reset them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginStats {

	/** {@link #getSource()}: on the core's class path - the war's {@code WEB-INF/lib}, a filter-mode application's. */
	public static final String SOURCE_BUNDLED = "bundled";

	/** {@link #getSource()}: from the node's plugin directory, {@code FRONTCACHE_HOME/plugins/<id>/}. */
	public static final String SOURCE_DROPPED_IN = "dropped-in";

	/** {@link #getState()}: a plugin directory that {@code front-cache.plugins} does not list - found, not loaded. */
	public static final String STATE_NOT_ENABLED = "not-enabled";

	/** {@link #getDecideState()}: its predicates answer. */
	public static final String DECIDE_OK = "ok";

	/** {@link #getDecideState()}: switched off for going over its budget or throwing - its rules do not match. */
	public static final String DECIDE_OVER_BUDGET = "over-budget";

	private String id;
	private String name;
	private String version;
	private String vendor;
	private String license;
	private int pluginApi;
	private List<String> kinds = new ArrayList<>();
	private boolean requestPath;
	private String state;
	private String reason;
	private boolean legacy;
	private long eventsDelivered;
	private long eventsDropped;
	private long errors;
	private String config;
	private String configStatus;
	private List<String> configErrors = new ArrayList<>();
	private long configLoaded;
	private boolean serves;
	private List<String> cookies = new ArrayList<>();
	private List<String> challenges = new ArrayList<>();
	private long served;
	private long serveRefused;
	private long serveFailed;
	private String source = SOURCE_BUNDLED;
	private String directory;
	private List<String> jars = new ArrayList<>();
	private boolean pinned;
	private List<String> predicates = new ArrayList<>();
	private String decideState;
	private String decideReason;
	private long decideBudgetMicros;
	private double decideP99Micros = -1;
	private long decideCalls;
	private long decideErrors;
	private long decideSwitchedOff;
	private long decideRetryAt;

	public PluginStats() { // for JSON mapper
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getVendor() {
		return vendor;
	}

	public void setVendor(String vendor) {
		this.vendor = vendor;
	}

	/** SPDX, as the plugin states it. */
	public String getLicense() {
		return license;
	}

	public void setLicense(String license) {
		this.license = license;
	}

	public int getPluginApi() {
		return pluginApi;
	}

	public void setPluginApi(int pluginApi) {
		this.pluginApi = pluginApi;
	}

	/** The declared kinds, as a manifest spells them: {@code observe}, {@code manage}, ... */
	public List<String> getKinds() {
		return kinds;
	}

	public void setKinds(List<String> kinds) {
		this.kinds = (null == kinds) ? new ArrayList<>() : kinds;
	}

	/** Whether a declared kind runs on the request path (decide / transform / replace) - shown as a badge. */
	public boolean isRequestPath() {
		return requestPath;
	}

	public void setRequestPath(boolean requestPath) {
		this.requestPath = requestPath;
	}

	/** {@code loaded}, {@code started}, {@code failed}, {@code refused}, {@code stopped} or {@value #STATE_NOT_ENABLED}. */
	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	/** Why it failed or was refused; null otherwise. */
	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	/** A pre-2.13 {@code FrontcacheModule} without a manifest, run through the deprecated adapter. */
	public boolean isLegacy() {
		return legacy;
	}

	public void setLegacy(boolean legacy) {
		this.legacy = legacy;
	}

	public long getEventsDelivered() {
		return eventsDelivered;
	}

	public void setEventsDelivered(long eventsDelivered) {
		this.eventsDelivered = eventsDelivered;
	}

	public long getEventsDropped() {
		return eventsDropped;
	}

	public void setEventsDropped(long eventsDropped) {
		this.eventsDropped = eventsDropped;
	}

	public long getErrors() {
		return errors;
	}

	public void setErrors(long errors) {
		this.errors = errors;
	}

	/** The plugin's config file under {@code conf/}, as its manifest names it; null when it has none. */
	public String getConfig() {
		return config;
	}

	public void setConfig(String config) {
		this.config = config;
	}

	/**
	 * The last read of {@link #getConfig()}: {@code ok}, {@code absent} (no file - the plugin got an empty config),
	 * {@code refused} (see {@link #getConfigErrors()}; at a start the plugin is then not started, on a reload it keeps
	 * the config it had), or null when it has not been read yet or the plugin has no config.
	 */
	public String getConfigStatus() {
		return configStatus;
	}

	public void setConfigStatus(String configStatus) {
		this.configStatus = configStatus;
	}

	/** Why the last read was refused; empty otherwise. */
	public List<String> getConfigErrors() {
		return configErrors;
	}

	public void setConfigErrors(List<String> configErrors) {
		this.configErrors = (null == configErrors) ? new ArrayList<>() : new ArrayList<>(configErrors);
	}

	/** When the config the plugin runs on was accepted, epoch milliseconds; 0 when never. */
	public long getConfigLoaded() {
		return configLoaded;
	}

	public void setConfigLoaded(long configLoaded) {
		this.configLoaded = configLoaded;
	}

	/** {@code serve}: whether its endpoint answers {@code /fc-x/<id>/} - false while it is not started. */
	public boolean isServes() {
		return serves;
	}

	public void setServes(boolean serves) {
		this.serves = serves;
	}

	/** {@code serve}: the cookies its manifest names - the only ones it reads, sets and has signed. */
	public List<String> getCookies() {
		return cookies;
	}

	public void setCookies(List<String> cookies) {
		this.cookies = (null == cookies) ? new ArrayList<>() : new ArrayList<>(cookies);
	}

	/** {@code serve}: the challenge providers its manifest offers to a {@code challenge <provider>} guard rule. */
	public List<String> getChallenges() {
		return challenges;
	}

	public void setChallenges(List<String> challenges) {
		this.challenges = (null == challenges) ? new ArrayList<>() : new ArrayList<>(challenges);
	}

	/** {@code serve}: requests its endpoint answered. */
	public long getServed() {
		return served;
	}

	public void setServed(long served) {
		this.served = served;
	}

	/** {@code serve}: requests refused before its endpoint - over its concurrency cap (503) or body limit (413). */
	public long getServeRefused() {
		return serveRefused;
	}

	public void setServeRefused(long serveRefused) {
		this.serveRefused = serveRefused;
	}

	/** {@code serve}: requests its endpoint failed - it threw, or wrote more than the node buffers (503). */
	public long getServeFailed() {
		return serveFailed;
	}

	public void setServeFailed(long serveFailed) {
		this.serveFailed = serveFailed;
	}

	/** {@value #SOURCE_BUNDLED} or {@value #SOURCE_DROPPED_IN}. */
	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	/** A dropped-in plugin's directory, relative to the plugin directory's parent ({@code plugins/geoip}); else null. */
	public String getDirectory() {
		return directory;
	}

	public void setDirectory(String directory) {
		this.directory = directory;
	}

	/** A dropped-in plugin's jars, each {@code <file name> <sha256>}; empty for a bundled one. */
	public List<String> getJars() {
		return jars;
	}

	public void setJars(List<String> jars) {
		this.jars = (null == jars) ? new ArrayList<>() : jars;
	}

	/** Whether {@code front-cache.plugins.<id>.sha256} pinned its jars (and they matched - else it is refused). */
	public boolean isPinned() {
		return pinned;
	}

	public void setPinned(boolean pinned) {
		this.pinned = pinned;
	}

	/** {@code decide}: the names of the {@code ext:<name>:} predicates it offers; empty until it starts. */
	public List<String> getPredicates() {
		return predicates;
	}

	public void setPredicates(List<String> predicates) {
		this.predicates = (null == predicates) ? new ArrayList<>() : predicates;
	}

	/** {@code decide}: {@value #DECIDE_OK} or {@value #DECIDE_OVER_BUDGET}; null for a plugin that does not decide. */
	public String getDecideState() {
		return decideState;
	}

	public void setDecideState(String decideState) {
		this.decideState = decideState;
	}

	/** {@code decide}: why it was last switched off - the p99 or the error rate; null when it never was. */
	public String getDecideReason() {
		return decideReason;
	}

	public void setDecideReason(String decideReason) {
		this.decideReason = decideReason;
	}

	/** {@code decide}: its budget, {@code front-cache.plugins.<id>.decide-budget}, in microseconds. */
	public long getDecideBudgetMicros() {
		return decideBudgetMicros;
	}

	public void setDecideBudgetMicros(long decideBudgetMicros) {
		this.decideBudgetMicros = decideBudgetMicros;
	}

	/** {@code decide}: its predicates' p99 over the last minute judged, in microseconds; -1 before the first. */
	public double getDecideP99Micros() {
		return decideP99Micros;
	}

	public void setDecideP99Micros(double decideP99Micros) {
		this.decideP99Micros = decideP99Micros;
	}

	/** {@code decide}: calls into its predicates since the process started. */
	public long getDecideCalls() {
		return decideCalls;
	}

	public void setDecideCalls(long decideCalls) {
		this.decideCalls = decideCalls;
	}

	/** {@code decide}: calls that threw. */
	public long getDecideErrors() {
		return decideErrors;
	}

	public void setDecideErrors(long decideErrors) {
		this.decideErrors = decideErrors;
	}

	/** {@code decide}: how many times it was switched off. */
	public long getDecideSwitchedOff() {
		return decideSwitchedOff;
	}

	public void setDecideSwitchedOff(long decideSwitchedOff) {
		this.decideSwitchedOff = decideSwitchedOff;
	}

	/** {@code decide}: while {@value #DECIDE_OVER_BUDGET}, when it is tried again (epoch millis); 0 otherwise. */
	public long getDecideRetryAt() {
		return decideRetryAt;
	}

	public void setDecideRetryAt(long decideRetryAt) {
		this.decideRetryAt = decideRetryAt;
	}
}
