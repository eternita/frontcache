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
package org.frontcache.plugin.testkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.TreeSet;

import org.frontcache.plugin.testkit.fixtures.BrokenPlugins;
import org.frontcache.plugin.testkit.fixtures.WellBehavedContract;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/**
 * The contract catches what it promises to: each broken plugin of {@link BrokenPlugins} fails exactly the contract
 * tests about its fault - and {@link WellBehavedContract} is the other half: a well-behaved plugin passes every one.
 */
public class PluginContractTest {

	private static Set<String> failures(Class<?> contract) {
		Result result = new JUnitCore().run(contract);
		Set<String> failed = new TreeSet<>();
		for (Failure f : result.getFailures())
			failed.add(f.getDescription().getMethodName());
		return failed;
	}

	@Test
	public void aWellBehavedPluginPassesEveryContractTest() {
		Result result = new JUnitCore().run(WellBehavedContract.class);
		assertTrue("ran no contract test", result.getRunCount() > 10);
		assertEquals(result.getFailures().toString(), 0, result.getFailureCount());
	}

	@Test
	public void aPredicateOverItsBudgetFails() {
		Set<String> failed = failures(BrokenPlugins.SlowPredicateContract.class);
		assertTrue(failed.toString(), failed.contains("predicatesStayUnderTheBudget"));
		assertTrue(failed.toString(), !failed.contains("everyPredicateArgumentParses"));
	}

	@Test
	public void aPredicateThatThrowsOnTheEmptyViewFails() {
		Set<String> failed = failures(BrokenPlugins.NullBlindPredicateContract.class);
		assertTrue(failed.toString(), failed.contains("predicatesAnswerAViewWithNothingBehindIt"));
		assertTrue("it throws on the corpus's empty view too", failed.contains("predicatesStayUnderTheBudget"));
	}

	@Test
	public void aListenerThatBlocksFails() {
		Set<String> failed = failures(BrokenPlugins.BlockingListenerContract.class);
		assertTrue(failed.toString(), failed.contains("listenersTakeABatchOfWhatTheySubscribeTo"));
		assertTrue(failed.toString(), !failed.contains("listenersTakeAnEmptyBatch"));
	}

	@Test
	public void aConfigThatIsNotRefusedByBothFails() {
		Set<String> failed = failures(BrokenPlugins.LaxConfigContract.class);
		assertTrue(failed.toString(), failed.contains("refusedConfigsAreRefusedWholeWithAReason"));
	}

	@Test
	public void aKindItDoesNotDeclareFailsEveryTest() {
		Result result = new JUnitCore().run(BrokenPlugins.UndeclaredEndpointContract.class);
		assertEquals(result.getRunCount(), result.getFailureCount());
		for (Failure f : result.getFailures())
			assertTrue(f.getMessage(), f.getException() instanceof PluginRefusedException);
	}

	@Test
	public void aTaskThatThrowsIsCountedAndKeepsItsSchedule() {
		Set<String> failed = failures(BrokenPlugins.LeakyContract.class);
		assertTrue(failed.toString(), failed.contains("startsStopsAndStartsAgain"));
	}
}
