/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tags;

@SuppressWarnings("serial")
public class WebComponentTag extends WebComponentSupport {

	public void setMaxage(String maxage) {
		super.maxage = maxage;
	}

	public void setTags(String tags) {
		super.tags = tags;
	}

	public void setRefresh(String refresh) {
		super.refresh = refresh;
	}

	public void setLevel(String level) {
		super.level = level;
	}
}