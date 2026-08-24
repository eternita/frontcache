/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.model;

/**
 * One edge the realtime page subscribes to, serialized to JSON for the page.
 *
 * Only the edge URL travels, not the stream URL: the path the metrics stream lives on is
 * {@code DashboardStreamProxyServlet}'s business, and the browser is not allowed to pick it.
 *
 * Was {@code HystrixConnection}, and carried Netflix-era {@code auth} / {@code delay} fields that
 * Frontcache never set - they existed for pointing the vendored dashboard at an arbitrary Turbine
 * stream, which the console does not do.
 */
public class DashboardStream {

	private String name;
	private String url;

	public DashboardStream() {
	}

	public DashboardStream(String name, String url) {
		this.name = name;
		this.url = url;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((url == null) ? 0 : url.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;

		DashboardStream other = (DashboardStream) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;

		if (url == null) {
			if (other.url != null)
				return false;
		} else if (!url.equals(other.url))
			return false;

		return true;
	}

	@Override
	public String toString() {
		return "DashboardStream{name=" + name + ", url=" + url + "}";
	}

}
