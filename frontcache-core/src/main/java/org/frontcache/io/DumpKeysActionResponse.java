/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

public class DumpKeysActionResponse extends ActionResponse {


	public DumpKeysActionResponse() { // for JSON mapper
		setAction("dumping keys is started - will be saved to ./warmer dir");
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public void setOutputFile(String outputFile)
	{
		setAction("dumping keys is started - will be saved to " + outputFile);
	}

	/**
	 * Marks this response as "rejected because a dump is already running" - no new dump was started.
	 */
	public void setAlreadyInProgress()
	{
		setAction("dumping keys is already in progress - request ignored");
	}

}
