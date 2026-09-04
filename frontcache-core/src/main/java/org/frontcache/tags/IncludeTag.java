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
public class IncludeTag extends IncludeSupport {

   public void setUrl(Object url) {
      super.ulr = url;
   }

   public void setCall(Object callType) {
	   super.includeCallType = callType;
   }

   public void setClient(Object clientType) {
	   super.includeClientType = clientType;
   }

   /**
    * {@code combine="true"}, or a group name. Marks this fragment as one the origin can serve
    * batched with its siblings - see docs/archive/combine-reduce-proposal.md.
    */
   public void setCombine(Object combine) {
	   super.includeCombine = combine;
   }

}