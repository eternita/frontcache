/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix.fr;

import java.util.regex.Pattern;

public class FallbackConfigEntryImpl extends FallbackConfigEntry {

	private Pattern urlRegexpPattern;

	public FallbackConfigEntryImpl() { // for JSON mapper
		super();
	}

	public Pattern getUrlRegexpPattern() {
		return urlRegexpPattern;
	}

	public void setUrlRegexpPattern(Pattern urlRegexpPattern) {
		this.urlRegexpPattern = urlRegexpPattern;
	}


}
