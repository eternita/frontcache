/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.guard;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.RequestContext;

/**
 * What a matching guard rule does with the request: reject it, redirect it, or explicitly let it
 * through (an exemption placed above a broader rule).
 */
public class GuardAction {

	public enum Type {
		REJECT, REDIRECT, ALLOW
	}

	/** disposition written to the failed-requests log (the is_cached column) */
	public static final String DISPOSITION_REJECTED = "rejected";
	public static final String DISPOSITION_REDIRECTED = "redirected";
	public static final String DISPOSITION_DRY_RUN = "dry-run";

	private static final GuardAction ALLOW = new GuardAction(Type.ALLOW, 0, null, null);

	private final Type type;
	private final int status;
	private final String body;    // REJECT only
	private final String target;  // REDIRECT only - may contain ${...} placeholders

	private GuardAction(Type type, int status, String body, String target) {
		this.type = type;
		this.status = status;
		this.body = body;
		this.target = target;
	}

	public static GuardAction reject(int status, String body) {
		return new GuardAction(Type.REJECT, status, body, null);
	}

	public static GuardAction redirect(int status, String target) {
		return new GuardAction(Type.REDIRECT, status, null, target);
	}

	public static GuardAction allow() {
		return ALLOW;
	}

	public Type getType() {
		return type;
	}

	public int getStatus() {
		return status;
	}

	public String getBody() {
		return body;
	}

	/**
	 * @return redirect target as configured, placeholders NOT expanded
	 */
	public String getTarget() {
		return target;
	}

	/**
	 * @return the disposition string for the log line, or null for ALLOW (not logged)
	 */
	public String getDisposition() {
		switch (type)
		{
		case REJECT:
			return DISPOSITION_REJECTED;
		case REDIRECT:
			return DISPOSITION_REDIRECTED;
		default:
			return null;
		}
	}

	/**
	 * Expands ${uri} / ${query} / ${url} / ${url:enc} / ${host} in the redirect target.
	 *
	 * @param context current request
	 * @return target ready for the Location header
	 */
	public String resolveTarget(RequestContext context)
	{
		if (null == target || -1 == target.indexOf("${"))
			return target;

		String url = requestURL(context);
		String resolved = target;
		resolved = replace(resolved, "${url:enc}", urlEncode(url));
		resolved = replace(resolved, "${url}", url);
		resolved = replace(resolved, "${uri}", nullToEmpty(context.getRequestURI()));
		resolved = replace(resolved, "${query}", nullToEmpty(context.getRequestQueryString()));
		resolved = replace(resolved, "${host}", nullToEmpty(context.getFrontCacheHost()));
		return resolved;
	}

	/**
	 * Full request URL as the client sent it. Built here rather than taken from
	 * RequestContext.getCurrentRequestURL(), which FrontCacheEngine only populates later and only
	 * for cacheable requests - guards run before that.
	 */
	public static String requestURL(RequestContext context)
	{
		String protocol = context.getFrontCacheProtocol();
		if (null == protocol)
			protocol = "http";

		String host = nullToEmpty(context.getFrontCacheHost());

		String portStr = "";
		HttpServletRequest request = context.getRequest();
		if (null != request)
		{
			try {
				int port = request.getServerPort();
				if (!((80 == port && "http".equals(protocol)) || (443 == port && "https".equals(protocol))))
					portStr = ":" + port;
			} catch (Exception e) {
				// recycled request - the URL is still useful without the port
			}
		}

		return protocol + "://" + host + portStr + nullToEmpty(context.getRequestURI()) + nullToEmpty(context.getRequestQueryString());
	}

	private static String replace(String value, String placeholder, String replacement)
	{
		int idx = value.indexOf(placeholder);
		if (-1 == idx)
			return value;

		StringBuilder sb = new StringBuilder();
		int from = 0;
		while (-1 < idx)
		{
			sb.append(value, from, idx).append(replacement);
			from = idx + placeholder.length();
			idx = value.indexOf(placeholder, from);
		}
		sb.append(value, from, value.length());
		return sb.toString();
	}

	private static String urlEncode(String value)
	{
		try {
			return URLEncoder.encode(value, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			return value; // UTF-8 is always present
		}
	}

	private static String nullToEmpty(String value) {
		return (null != value) ? value : "";
	}

	@Override
	public String toString()
	{
		switch (type)
		{
		case REJECT:
			return "reject:" + status;
		case REDIRECT:
			return "redirect:" + status + " " + target;
		default:
			return "allow";
		}
	}

}
