/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the client IP address that rate limiting counts against.
 *
 * This is deliberately NOT {@link FCUtils#getClientIP(HttpServletRequest)}. That method walks
 * thirteen candidate headers and returns the first non-empty one, which is right for a
 * best-effort log column and unusable as a limiter key: every one of those headers is
 * client-supplied, so a client sending "X-Forwarded-For: &lt;random&gt;" on each request would get a
 * fresh counter on each request and defeat the limiter entirely - while passing every test that
 * does not try it. The two resolutions coexist on purpose; see docs/guard-rate-limit-proposal.md §3.
 *
 * The rules here:
 *
 * <ul>
 * <li>A forwarding header is read ONLY when the socket peer is a configured trusted proxy.
 *     Otherwise the peer address itself is the key and every header is ignored.</li>
 * <li>The header is walked RIGHT TO LEFT, returning the first entry that is not itself a trusted
 *     proxy. The leftmost entry - which most implementations use - is whatever the client wrote;
 *     the rightmost non-trusted entry is what the last proxy we trust actually observed.</li>
 * <li>The result is canonicalized so that the same address always produces the same key:
 *     IPv6 literals are expanded to one textual form, and IPv4-mapped IPv6 (::ffff:203.0.113.7)
 *     folds to its IPv4 form so a dual-stack container does not split one client in two.</li>
 * <li>No prefix, no subnet, no CIDR grouping: one address is one counter. The consequence - an
 *     IPv6 client holding a /64 can rotate addresses and evade the limit - is accepted and
 *     documented, in exchange for never limiting a shared-NAT office because of one user behind it.</li>
 * </ul>
 *
 * Never throws: an address that cannot be resolved returns null, and the caller declines to meter
 * that request rather than failing it.
 */
public class ClientIpResolver {

	public static final String TRUSTED_PROXIES_PROPERTY = "front-cache.client-ip.trusted-proxies";
	public static final String HEADER_PROPERTY = "front-cache.client-ip.header";

	private static final String DEFAULT_HEADER = "X-Forwarded-For";

	private static Logger logger = LoggerFactory.getLogger(ClientIpResolver.class);

	private static volatile ClientIpResolver instance;

	private final List<Cidr> trustedProxies;
	private final String headerName;

	public ClientIpResolver(String trustedProxiesCsv, String headerName) {
		this.trustedProxies = parseCidrs(trustedProxiesCsv);
		this.headerName = (null != headerName && 0 < headerName.trim().length()) ? headerName.trim() : DEFAULT_HEADER;
	}

	/**
	 * @return the resolver built from the current configuration, created on first use
	 */
	public static ClientIpResolver getInstance()
	{
		ClientIpResolver current = instance;
		if (null == current)
		{
			synchronized (ClientIpResolver.class) {
				current = instance;
				if (null == current)
					current = instance = fromConfig();
			}
		}
		return current;
	}

	/**
	 * Re-reads the configuration. Called from GuardRuleEngine.load(), so an edited
	 * frontcache.properties is picked up by the next engine reload rather than only by a restart.
	 */
	public static void reload() {
		instance = fromConfig();
	}

	/** for tests - installs a resolver without going through FCConfig */
	public static void install(ClientIpResolver resolver) {
		instance = resolver;
	}

	private static ClientIpResolver fromConfig()
	{
		return new ClientIpResolver(
				FCConfig.getProperty(TRUSTED_PROXIES_PROPERTY, ""),
				FCConfig.getProperty(HEADER_PROPERTY, DEFAULT_HEADER));
	}

	/**
	 * @return true when at least one trusted proxy is configured. Rate limiting behind a reverse
	 *         proxy with none configured keys every request on the proxy's own address, so the guard
	 *         engine warns about it at load time.
	 */
	public boolean hasTrustedProxies() {
		return !trustedProxies.isEmpty();
	}

	/**
	 * @param request current request; may be null or container-recycled
	 * @return canonical client address, or null when it cannot be established
	 */
	public String resolve(HttpServletRequest request)
	{
		if (null == request)
			return null;

		try {
			InetAddress peer = parseAddress(request.getRemoteAddr());
			if (null == peer)
				return null;

			if (!isTrusted(peer))
				return canonical(peer); // untrusted peer: its own address is the only trustworthy thing about it

			// Trusted peer. X-Frontcache-Client-IP first: a sibling Frontcache node sets it so the
			// real visitor - not the sibling - is what gets counted. It carries whatever that node
			// resolved, which is why it is honoured from a trusted peer only.
			InetAddress forwarded = firstUntrustedFromHeader(request, FCHeaders.X_FRONTCACHE_CLIENT_IP);
			if (null == forwarded)
				forwarded = firstUntrustedFromHeader(request, headerName);

			return canonical((null != forwarded) ? forwarded : peer);

		} catch (Exception e) {
			// recycled request (async include resolved after the response completed), or anything
			// else unexpected - undecidable, so the caller does not meter this request
			return null;
		}
	}

	/**
	 * Walks one header's entries right to left and returns the first that is not a trusted proxy.
	 * Uses getHeaders(), not getHeader(): X-Forwarded-For legitimately arrives as several header
	 * lines, and getHeader() would see only the first of them.
	 */
	private InetAddress firstUntrustedFromHeader(HttpServletRequest request, String name)
	{
		List<String> entries = new ArrayList<String>();

		Enumeration<String> values = request.getHeaders(name);
		if (null == values)
			return null;

		while (values.hasMoreElements())
		{
			String value = values.nextElement();
			if (null == value)
				continue;

			for (String entry : value.split(","))
			{
				String trimmed = entry.trim();
				if (0 < trimmed.length())
					entries.add(trimmed);
			}
		}

		for (int i = entries.size() - 1; 0 <= i; i--)
		{
			InetAddress address = parseAddress(entries.get(i));
			if (null == address)
				return null; // an unparseable hop (RFC 7239 "unknown"/obfuscated) ends the walk - we cannot see past it

			if (!isTrusted(address))
				return address;
		}

		return null;
	}

	private boolean isTrusted(InetAddress address)
	{
		for (Cidr cidr : trustedProxies)
			if (cidr.contains(address))
				return true;

		return false;
	}

	/**
	 * One canonical string per address. InetAddress has already folded IPv4-mapped IPv6 to
	 * Inet4Address, and getHostAddress() writes IPv6 in one fixed expanded form, so
	 * "2001:db8::1", "2001:DB8:0:0:0:0:0:1" and "[2001:db8::1]" all arrive here as one key.
	 */
	private static String canonical(InetAddress address) {
		return address.getHostAddress();
	}

	/**
	 * Parses an address literal only - never a host name, so this can never trigger a DNS lookup on
	 * the request thread from attacker-controlled input. Tolerates the forms that turn up in
	 * remoteAddr and forwarding headers: bracketed IPv6, a trailing port, an IPv6 zone id.
	 *
	 * @return the address, or null if the text is not an IP literal
	 */
	static InetAddress parseAddress(String text)
	{
		if (null == text)
			return null;

		String value = text.trim();
		if (0 == value.length())
			return null;

		if (value.startsWith("["))
		{
			int close = value.indexOf(']');
			if (-1 == close)
				return null;
			value = value.substring(1, close);           // [2001:db8::1]:443 -> 2001:db8::1
		}
		else
		{
			int colon = value.indexOf(':');
			if (-1 < colon && colon == value.lastIndexOf(':'))
				value = value.substring(0, colon);       // 203.0.113.7:1234 -> 203.0.113.7 (one colon = IPv4:port)
		}

		int zone = value.indexOf('%');
		if (-1 < zone)
			value = value.substring(0, zone);            // fe80::1%eth0 -> fe80::1

		if (0 == value.length())
			return null;

		try {
			return InetAddress.ofLiteral(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static List<Cidr> parseCidrs(String commaSeparated)
	{
		List<Cidr> cidrs = new ArrayList<Cidr>();

		if (null == commaSeparated)
			return cidrs;

		for (String token : commaSeparated.split(","))
		{
			String trimmed = token.trim();
			if (0 == trimmed.length())
				continue;

			Cidr cidr = Cidr.parse(trimmed);
			if (null == cidr)
			{
				// one bad entry must not silently widen or void the trust list
				logger.error("Ignoring unparseable entry '" + trimmed + "' in " + TRUSTED_PROXIES_PROPERTY
						+ " - expected an address or address/prefix, e.g. 10.0.0.0/8");
				continue;
			}

			cidrs.add(cidr);
		}

		return cidrs;
	}

	/**
	 * An address block, compared on raw address bytes so v4 and v6 never match each other.
	 */
	static final class Cidr {

		private final byte[] network;
		private final int prefixBits;

		private Cidr(byte[] network, int prefixBits) {
			this.network = network;
			this.prefixBits = prefixBits;
		}

		static Cidr parse(String text)
		{
			String value = text;
			int prefixBits = -1;

			int slash = value.lastIndexOf('/');
			if (-1 < slash)
			{
				try {
					prefixBits = Integer.parseInt(value.substring(slash + 1).trim());
				} catch (NumberFormatException e) {
					return null;
				}
				value = value.substring(0, slash).trim();
			}

			InetAddress address = parseAddress(value);
			if (null == address)
				return null;

			byte[] bytes = address.getAddress();
			int maxBits = bytes.length * 8;

			if (-1 == prefixBits)
				prefixBits = maxBits;   // a bare address is a single-host block

			if (0 > prefixBits || prefixBits > maxBits)
				return null;

			return new Cidr(bytes, prefixBits);
		}

		boolean contains(InetAddress address)
		{
			byte[] bytes;
			try {
				bytes = address.getAddress();
			} catch (Exception e) {
				return false;
			}

			if (bytes.length != network.length)
				return false; // different family - never a match, which is what keeps a v4 block from covering v6

			int fullBytes = prefixBits / 8;
			for (int i = 0; i < fullBytes; i++)
				if (bytes[i] != network[i])
					return false;

			int remainingBits = prefixBits % 8;
			if (0 == remainingBits)
				return true;

			int mask = 0xFF << (8 - remainingBits);
			return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
		}
	}
}
