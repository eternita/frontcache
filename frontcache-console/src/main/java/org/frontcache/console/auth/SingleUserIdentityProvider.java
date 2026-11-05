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
package org.frontcache.console.auth;

import java.util.Optional;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.web.ConsoleAuthenticator;

/**
 * The console's one login - {@code user} / {@code password} in frontcache-console.conf - as an identity provider: the
 * default when no console plugin provides one. {@link ConsoleAuthenticator} stays the one place that password is
 * compared.
 */
public final class SingleUserIdentityProvider implements ConsoleIdentityProvider {

	private final FrontcacheService frontcacheService;

	private final ConsoleAuthenticator authenticator;

	public SingleUserIdentityProvider(FrontcacheService frontcacheService) {
		this.frontcacheService = frontcacheService;
		this.authenticator = new ConsoleAuthenticator(frontcacheService);
	}

	@Override
	public Optional<ConsolePrincipal> authenticate(String user, String password, Channel channel) {

		if (!authenticator.matches(user, password))
			return Optional.empty();

		// the configured spelling, not the submitted one: matches() compared them exactly anyway
		return Optional.of(ConsolePrincipal.named(frontcacheService.getUser()));
	}
}
