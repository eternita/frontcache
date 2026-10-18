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
package org.frontcache.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * How the rate limiter decides which address to count.
 *
 * Most of this file is security tests rather than behaviour tests: the failure they guard against -
 * honouring a forwarding header from a peer we do not trust - is silent, defeats the limiter
 * completely, and is defeated by one line of curl.
 */
public class ClientIpResolverTest {

	private static final String LOCAL_PROXIES = "127.0.0.1/32,::1/128,10.0.0.0/8";

	// ---- untrusted peers: headers are ignored entirely ---------------------------------------

	@Test
	public void aSpoofedForwardingHeaderFromAnUntrustedPeerIsIgnored() {
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		// The whole point: a client that mints a random XFF per request must NOT get a fresh
		// counter per request.
		assertEquals("203.0.113.7", resolver.resolve(request("203.0.113.7", "X-Forwarded-For", "1.2.3.4")));
		assertEquals("203.0.113.7", resolver.resolve(request("203.0.113.7", "X-Forwarded-For", "5.6.7.8")));
	}

	@Test
	public void aSpoofedFrontcacheClientIpHeaderFromAnUntrustedPeerIsIgnored() {
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		assertEquals("203.0.113.7",
				resolver.resolve(request("203.0.113.7", FCHeaders.X_FRONTCACHE_CLIENT_IP, "1.2.3.4")));
	}

	@Test
	public void withNoTrustedProxiesEveryHeaderIsIgnored() {
		ClientIpResolver resolver = new ClientIpResolver("", "X-Forwarded-For");

		assertFalse(resolver.hasTrustedProxies());
		assertEquals("203.0.113.7", resolver.resolve(request("203.0.113.7", "X-Forwarded-For", "1.2.3.4")));
	}

	// ---- trusted peers: the header is read, right to left --------------------------------------

	@Test
	public void aTrustedProxyForwardsTheClientAddress() {
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "203.0.113.7")));
	}

	@Test
	public void aChainIsWalkedRightToLeftToTheFirstUntrustedHop() {
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		// The leftmost entry is whatever the client wrote - taking it, as most implementations do,
		// is exactly the spoofable choice this resolver exists to avoid.
		assertEquals("203.0.113.7",
				resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "9.9.9.9, 203.0.113.7, 10.0.0.4")));
	}

	@Test
	public void aHeaderSplitOverSeveralLinesIsStillOneChain() {
		// XFF legitimately arrives as several header lines; getHeader() would see only the first
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
		headers.put("x-forwarded-for", new ArrayList<String>(java.util.Arrays.asList("9.9.9.9", "203.0.113.7, 10.0.0.4")));

		assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", headers)));
	}

	@Test
	public void anAllTrustedChainFallsBackToThePeer() {
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		assertEquals("10.0.0.4", resolver.resolve(request("10.0.0.4", "X-Forwarded-For", "10.0.0.5, 127.0.0.1")));
	}

	@Test
	public void anUnparseableHopEndsTheWalk() {
		// RFC 7239 allows obfuscated identifiers ("unknown", "_hidden"); we cannot see past one,
		// so we do not guess - the trusted peer is counted instead
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "203.0.113.7, unknown")));
	}

	@Test
	public void frontcacheClientIpWinsOverTheForwardingHeaderFromATrustedPeer() {
		// browser -> FC standalone -> FC webfilter: the sibling node sets this so the visitor, not
		// the sibling, is what gets counted
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
		headers.put(FCHeaders.X_FRONTCACHE_CLIENT_IP, Collections.singletonList("203.0.113.7"));
		headers.put("x-forwarded-for", Collections.singletonList("198.51.100.9"));

		assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", headers)));
	}

	@Test
	public void aCustomHeaderNameIsHonoured() {
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "True-Client-IP");

		assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", "True-Client-IP", "203.0.113.7")));
		assertEquals("the configured header is the only one read", "127.0.0.1",
				resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "203.0.113.7")));
	}

	// ---- canonicalization: the same address is always the same key ----------------------------

	@Test
	public void ipv6LiteralsInDifferentFormsAreOneKey() {
		ClientIpResolver resolver = new ClientIpResolver("", "X-Forwarded-For");

		String expanded = resolver.resolve(request("2001:db8::1", Collections.<String, List<String>>emptyMap()));
		assertEquals(expanded, resolver.resolve(request("2001:DB8:0:0:0:0:0:1", Collections.<String, List<String>>emptyMap())));
		assertEquals(expanded, resolver.resolve(request("[2001:db8::1]", Collections.<String, List<String>>emptyMap())));
		assertEquals(expanded, resolver.resolve(request("[2001:db8::1]:44321", Collections.<String, List<String>>emptyMap())));
	}

	@Test
	public void anIpv4MappedIpv6AddressFoldsToItsIpv4Form() {
		// a dual-stack container must not split one client into two counters
		ClientIpResolver resolver = new ClientIpResolver("", "X-Forwarded-For");

		assertEquals("203.0.113.7", resolver.resolve(request("::ffff:203.0.113.7", Collections.<String, List<String>>emptyMap())));
	}

	@Test
	public void aPortOrZoneIdIsStripped() {
		ClientIpResolver resolver = new ClientIpResolver("", "X-Forwarded-For");

		assertEquals("203.0.113.7", resolver.resolve(request("203.0.113.7:51234", Collections.<String, List<String>>emptyMap())));
		assertEquals("fe80:0:0:0:0:0:0:1", resolver.resolve(request("fe80::1%eth0", Collections.<String, List<String>>emptyMap())));
	}

	@Test
	public void twoAddressesInOneSlash64AreTwoSeparateKeys() {
		// The requested behaviour, asserted so it cannot drift: one address is one counter, with no
		// subnet grouping. The known cost - an IPv6 client can rotate inside its /64 and evade the
		// limit - is documented, not accidental.
		ClientIpResolver resolver = new ClientIpResolver("", "X-Forwarded-For");

		String a = resolver.resolve(request("2001:db8:1:2::a", Collections.<String, List<String>>emptyMap()));
		String b = resolver.resolve(request("2001:db8:1:2::b", Collections.<String, List<String>>emptyMap()));

		assertFalse("addresses in the same /64 must not share a counter", a.equals(b));
	}

	// ---- undecidable input is not metered ------------------------------------------------------

	@Test
	public void anUnresolvableRequestYieldsNoKey() {
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		assertNull(resolver.resolve(null));
		assertNull("a host name is never an address - and must never trigger a DNS lookup",
				resolver.resolve(request("some.host.name", Collections.<String, List<String>>emptyMap())));
		assertNull(resolver.resolve(request(null, Collections.<String, List<String>>emptyMap())));
	}

	@Test
	public void aRecycledRequestIsNotMetered() {
		ClientIpResolver resolver = new ClientIpResolver(LOCAL_PROXIES, "X-Forwarded-For");

		HttpServletRequest recycled = (HttpServletRequest) Proxy.newProxyInstance(
				ClientIpResolverTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						throw new IllegalStateException("request does not exist any more");
					}
				});

		assertNull(resolver.resolve(recycled));
	}

	// ---- trusted-proxy parsing -----------------------------------------------------------------

	@Test
	public void prefixLengthsAreRespected() {
		ClientIpResolver resolver = new ClientIpResolver("10.1.0.0/16", "X-Forwarded-For");

		assertEquals("inside the block: header read", "203.0.113.7",
				resolver.resolve(request("10.1.99.4", "X-Forwarded-For", "203.0.113.7")));
		assertEquals("outside the block: header ignored", "10.2.0.4",
				resolver.resolve(request("10.2.0.4", "X-Forwarded-For", "203.0.113.7")));
	}

	@Test
	public void aBareAddressIsASingleHostBlock() {
		ClientIpResolver resolver = new ClientIpResolver("127.0.0.1", "X-Forwarded-For");

		assertTrue(resolver.hasTrustedProxies());
		assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "203.0.113.7")));
		assertEquals("127.0.0.2", resolver.resolve(request("127.0.0.2", "X-Forwarded-For", "203.0.113.7")));
	}

	@Test
	public void anIpv4BlockNeverCoversIpv6() {
		ClientIpResolver resolver = new ClientIpResolver("0.0.0.0/0", "X-Forwarded-For");

		assertEquals("2001:db8:0:0:0:0:0:1",
				resolver.resolve(request("2001:db8::1", "X-Forwarded-For", "203.0.113.7")));
	}

	@Test
	public void aBrokenTrustEntryIsDroppedAndTheRestStillWork() {
		ClientIpResolver resolver = new ClientIpResolver("not-an-address, 127.0.0.1/32, 10.0.0.0/99", "X-Forwarded-For");

		assertTrue(resolver.hasTrustedProxies());
		assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "203.0.113.7")));
		assertEquals("the unparseable entries must not widen the trust list", "10.0.0.4",
				resolver.resolve(request("10.0.0.4", "X-Forwarded-For", "203.0.113.7")));
	}

	// ---- a real deployment shape: Cloudflare in front of nginx in front of Frontcache ----------

	/**
	 * The topology this was configured against: client -> Cloudflare -> nginx -> Frontcache, with
	 * nginx proxying to 127.0.0.1:9080 and setting {@code X-Forwarded-For $proxy_add_x_forwarded_for},
	 * which APPENDS the peer it saw. So Frontcache receives "&lt;visitor&gt;, &lt;cloudflare edge&gt;"
	 * and both hops in front of it have to be trusted for the visitor to be what gets counted.
	 *
	 * Addresses here are documentation ranges standing in for the real ones; what is being pinned is
	 * the SHAPE, which was taken from production logs.
	 */
	private static final String CDN_AND_LOCAL = "127.0.0.1/32,::1/128,198.51.100.0/24,203.0.113.9/32";

	@Test
	public void behindACdnAndNginxTheVisitorIsWhatGetsCounted() {
		ClientIpResolver resolver = new ClientIpResolver(CDN_AND_LOCAL, "X-Forwarded-For");

		// nginx appended the CDN edge (198.51.100.7) to the chain the CDN sent
		assertEquals("192.0.2.55",
				resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "192.0.2.55, 198.51.100.7")));
	}

	@Test
	public void aNodeThatRefetchesThroughItsOwnPublicNameIsNotCountedAsTheClient() {
		// The chain that appears when a node's origin URL resolves back through the CDN to itself:
		//   visitor, cdn-edge, THE NODE'S OWN PUBLIC IP, cdn-edge
		// Without the node's own address in the trust list the walk stops there, and every one of
		// those requests - 18% of traffic on the deployment this was measured against - would share a
		// single counter, i.e. the node would rate-limit itself.
		ClientIpResolver resolver = new ClientIpResolver(CDN_AND_LOCAL, "X-Forwarded-For");

		assertEquals("192.0.2.55", resolver.resolve(request("127.0.0.1", "X-Forwarded-For",
				"192.0.2.55, 198.51.100.7,203.0.113.9, 198.51.100.31")));

		// ...and with it missing, this is the wrong answer that would be counted instead
		ClientIpResolver withoutSelf = new ClientIpResolver("127.0.0.1/32,198.51.100.0/24", "X-Forwarded-For");
		assertEquals("203.0.113.9", withoutSelf.resolve(request("127.0.0.1", "X-Forwarded-For",
				"192.0.2.55, 198.51.100.7,203.0.113.9, 198.51.100.31")));
	}

	@Test
	public void aRequestThatBypassesTheCdnIsCountedOnItsRealAddress() {
		// 0.011% of traffic on the measured deployment reaches the origin directly, skipping the CDN -
		// scanners that resolved the origin address. nginx still appends the true peer, so the
		// right-to-left walk lands on it however the attacker forged the rest of the header. This is
		// the case a CDN-specific header (CF-Connecting-IP and friends) cannot cover: nothing
		// overwrites it when the CDN is not in the path.
		ClientIpResolver resolver = new ClientIpResolver(CDN_AND_LOCAL, "X-Forwarded-For");

		assertEquals("192.0.2.200", resolver.resolve(request("127.0.0.1", "X-Forwarded-For",
				"10.9.9.9, 192.0.2.200")));
	}

	@Test
	public void anIpv6VisitorBehindTheCdnKeepsItsOwnCounter() {
		// real traffic on the measured deployment is a mix; the CDN hop is v4 either way
		ClientIpResolver resolver = new ClientIpResolver(CDN_AND_LOCAL, "X-Forwarded-For");

		String a = resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "2001:db8:1:2::a, 198.51.100.7"));
		String b = resolver.resolve(request("127.0.0.1", "X-Forwarded-For", "2001:db8:1:2::b, 198.51.100.7"));

		assertEquals("2001:db8:1:2:0:0:0:a", a);
		assertFalse(a.equals(b));
	}

	// ---- isTrustedPeer, for X-Forwarded-Proto ----------------------------------------------------

	/**
	 * The scheme of an emitted URL is read from X-Forwarded-Proto under exactly this rule, and for a
	 * sharper reason than the client IP: that value goes into a redirect a browser follows, so a
	 * client able to set it could choose the scheme of a URL Frontcache vouches for.
	 * See docs/archive/guard-redirect-url-host-fix.md.
	 */
	@Test
	public void aPeerInsideAConfiguredCidrIsTrusted() {
		ClientIpResolver resolver = new ClientIpResolver(CDN_AND_LOCAL, "X-Forwarded-For");

		assertTrue(resolver.isTrustedPeer(request("127.0.0.1", "X-Forwarded-Proto", "https")));
		assertTrue(resolver.isTrustedPeer(request("198.51.100.7", "X-Forwarded-Proto", "https")));
	}

	@Test
	public void aPeerOutsideEveryConfiguredCidrIsNotTrusted() {
		ClientIpResolver resolver = new ClientIpResolver(CDN_AND_LOCAL, "X-Forwarded-For");

		assertFalse(resolver.isTrustedPeer(request("203.0.113.7", "X-Forwarded-Proto", "https")));
	}

	/**
	 * With no trusted-proxy list, nothing is trusted - the deployment states its scheme with
	 * front-cache.public-scheme instead. A resolver that trusted everything here would let any
	 * client pick the scheme.
	 */
	@Test
	public void nothingIsTrustedWithoutAConfiguredList() {
		ClientIpResolver resolver = new ClientIpResolver("", "X-Forwarded-For");

		assertFalse(resolver.isTrustedPeer(request("127.0.0.1", "X-Forwarded-Proto", "https")));
	}

	@Test
	public void anUnusableRequestIsNotTrusted() {
		ClientIpResolver resolver = new ClientIpResolver(CDN_AND_LOCAL, "X-Forwarded-For");

		assertFalse(resolver.isTrustedPeer(null));
		assertFalse("a recycled request answers null for its peer",
				resolver.isTrustedPeer(request(null, "X-Forwarded-Proto", "https")));
		assertFalse(resolver.isTrustedPeer(request("not-an-address", "X-Forwarded-Proto", "https")));
	}

	// ---- stand-in request ------------------------------------------------------------------------

	private static HttpServletRequest request(String remoteAddr, String headerName, String headerValue) {
		Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
		headers.put(headerName.toLowerCase(), Collections.singletonList(headerValue));
		return request(remoteAddr, headers);
	}

	/**
	 * A dynamic proxy, matching how the other guard tests stand in for a servlet request - the core
	 * module has no mocking library and HttpServletRequest has far too many methods to implement.
	 * Header lookup is case-insensitive, as a container's is.
	 */
	private static HttpServletRequest request(final String remoteAddr, final Map<String, List<String>> headers) {
		return (HttpServletRequest) Proxy.newProxyInstance(
				ClientIpResolverTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						switch (method.getName())
						{
						case "getRemoteAddr":
							return remoteAddr;
						case "getHeaders":
						{
							List<String> values = lookup((String) args[0]);
							return Collections.enumeration((null != values) ? values : Collections.<String>emptyList());
						}
						case "getHeader":
						{
							List<String> values = lookup((String) args[0]);
							return (null != values && !values.isEmpty()) ? values.get(0) : null;
						}
						default:
							return null;
						}
					}

					private List<String> lookup(String name) {
						for (Map.Entry<String, List<String>> entry : headers.entrySet())
							if (entry.getKey().equalsIgnoreCase(name))
								return entry.getValue();

						return null;
					}
				});
	}

}
