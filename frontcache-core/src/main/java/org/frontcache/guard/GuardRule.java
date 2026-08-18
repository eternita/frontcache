/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.guard;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.frontcache.core.RequestContext;

/**
 * A guard rule: name | condition | action.
 *
 * The name is the rule's identity everywhere else - it is written to the failed-requests log as
 * the reason and becomes the metric in the Kibana "Frontcache Rejected Requests" dashboard.
 * Renaming a rule renames the metric.
 */
public class GuardRule {

	private final String name;
	private final List<GuardPredicate> predicates;
	private final GuardAction action;
	private final boolean dryRun;

	// hit count since start; in-memory only, reset on restart (by design)
	private final LongAdder hits = new LongAdder();

	public GuardRule(String name, List<GuardPredicate> predicates, GuardAction action, boolean dryRun) {
		this.name = name;
		this.predicates = Collections.unmodifiableList(predicates);
		this.action = action;
		this.dryRun = dryRun;
	}

	/**
	 * @return true when EVERY predicate holds (predicates are AND-ed, evaluation short-circuits)
	 */
	public boolean matches(RequestContext context)
	{
		for (GuardPredicate predicate : predicates)
			if (!predicate.test(context))
				return false;

		return true;
	}

	public String getName() {
		return name;
	}

	public List<GuardPredicate> getPredicates() {
		return predicates;
	}

	public GuardAction getAction() {
		return action;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public long getHits() {
		return hits.sum();
	}

	public void countHit() {
		hits.increment();
	}

	public String getCondition()
	{
		StringBuilder sb = new StringBuilder();
		for (GuardPredicate predicate : predicates)
		{
			if (0 < sb.length())
				sb.append(" ; ");
			sb.append(predicate.describe());
		}
		return sb.toString();
	}

	/**
	 * @return flat, JSON-friendly snapshot of this rule for the management API and the console
	 */
	public GuardRuleEntry toEntry() {
		return new GuardRuleEntry(name, getCondition(), action.toString(), dryRun, getHits());
	}

	@Override
	public String toString() {
		return name + " | " + getCondition() + " | " + action + (dryRun ? " | dry-run" : "");
	}

}
