/*
 * ProGuardCORE -- library to process Java bytecode.
 *
 * Copyright (c) 2002-2022 Guardsquare NV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package proguard.analysis.cpa

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import proguard.analysis.cpa.algorithms.CpaAlgorithm
import proguard.analysis.cpa.defaults.ControllableAbortOperator
import proguard.analysis.cpa.defaults.DefaultReachedSet
import proguard.analysis.cpa.defaults.DepthFirstWaitlist
import proguard.analysis.cpa.defaults.MergeJoinOperator
import proguard.analysis.cpa.defaults.MergeSepOperator
import proguard.analysis.cpa.defaults.NeverAbortOperator
import proguard.analysis.cpa.defaults.SimpleCpa
import proguard.analysis.cpa.defaults.StaticPrecisionAdjustment
import proguard.analysis.cpa.defaults.StopAlwaysOperator
import proguard.analysis.cpa.defaults.StopContainedOperator
import proguard.analysis.cpa.defaults.StopSepOperator
import proguard.testutils.cpa.BoundedAdditiveTransferRelation
import proguard.testutils.cpa.IntegerAbstractState

class CpaAlgorithmTest : FreeSpec({

    val transferRelation = BoundedAdditiveTransferRelation(2, 10)

    val mergeJoinOperator = MergeJoinOperator<IntegerAbstractState>()
    val mergeSepOperator = MergeSepOperator<IntegerAbstractState>()

    val stopAlwaysOperator = StopAlwaysOperator<IntegerAbstractState>()
    val stopContainedOperator = StopContainedOperator<IntegerAbstractState>()
    val stopSepOperator = StopSepOperator<IntegerAbstractState>()

    val precisionAdjustment = StaticPrecisionAdjustment()

    "Reachability set up works as expected" {
        val waitlist = DepthFirstWaitlist<IntegerAbstractState>()
        waitlist.add(IntegerAbstractState(0))
        val reachedset = DefaultReachedSet<IntegerAbstractState>()
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeSepOperator,
                stopContainedOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // the test should return all states reachable from 0
        reachedset.asCollection() shouldBe setOf(
            IntegerAbstractState(2),
            IntegerAbstractState(4),
            IntegerAbstractState(6),
            IntegerAbstractState(8),
            IntegerAbstractState(10),
        )

        val orderedReachedSet: MutableList<IntegerAbstractState> = ArrayList()
        reachedset.asCollection().forEach { orderedReachedSet.add(it) }

        // the order of the elements should be deterministic
        orderedReachedSet shouldBe listOf(
            IntegerAbstractState(2),
            IntegerAbstractState(4),
            IntegerAbstractState(6),
            IntegerAbstractState(8),
            IntegerAbstractState(10),
        )
    }

    "Merging works as expected" {
        val waitlist = DepthFirstWaitlist<IntegerAbstractState>()
        waitlist.add(IntegerAbstractState(0))
        val reachedset = DefaultReachedSet<IntegerAbstractState>()
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeJoinOperator,
                stopContainedOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // the test should return the join of all states reachable from 0
        reachedset.asCollection() shouldBe setOf(IntegerAbstractState(10))
    }

    "Always stopping only merges the reached states" {
        val waitlist = DepthFirstWaitlist<IntegerAbstractState>()
        waitlist.add(IntegerAbstractState(0))
        val reachedset = DefaultReachedSet<IntegerAbstractState>()
        reachedset.add(IntegerAbstractState(0))
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeJoinOperator,
                stopAlwaysOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // the algorithm iterates until the join of the newly reached state with previous reached states converges
        reachedset.asCollection() shouldBe setOf(IntegerAbstractState(10))

        waitlist.clear()
        waitlist.add(IntegerAbstractState(0))
        reachedset.clear()
        reachedset.add(IntegerAbstractState(0))
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeSepOperator,
                stopAlwaysOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // since merging does not generate a new state, the newly reached states are not added because of always stopping
        reachedset.asCollection() shouldBe setOf(IntegerAbstractState(0))

        waitlist.clear()
        waitlist.add(IntegerAbstractState(0))
        reachedset.clear()
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeJoinOperator,
                stopAlwaysOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // if there were no reached states, there is nothing we can add as the result of the join
        reachedset.asCollection() shouldBe setOf()
    }

    "Separate stopping runs until the new states are covered by the reached ones" {
        val waitlist = DepthFirstWaitlist<IntegerAbstractState>()
        waitlist.add(IntegerAbstractState(0))
        val reachedset = DefaultReachedSet<IntegerAbstractState>()
        reachedset.add(IntegerAbstractState(20))
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeSepOperator,
                stopSepOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // 20 covers all reachable states from 0, hence the reached set remains the same
        reachedset.asCollection() shouldBe setOf(IntegerAbstractState(20))

        waitlist.clear()
        waitlist.add(IntegerAbstractState(0))
        reachedset.clear()
        reachedset.add(IntegerAbstractState(0))
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeSepOperator,
                stopSepOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // here, the algorithm runs until 10 is reached as it covers all other reachable states
        reachedset.asCollection() shouldBe setOf(
            IntegerAbstractState(0),
            IntegerAbstractState(2),
            IntegerAbstractState(4),
            IntegerAbstractState(6),
            IntegerAbstractState(8),
            IntegerAbstractState(10),
        )
    }

    "Equality stopping runs until the new states are included by the reached ones" {
        val waitlist = DepthFirstWaitlist<IntegerAbstractState>()
        waitlist.add(IntegerAbstractState(0))
        val reachedset = DefaultReachedSet<IntegerAbstractState>()
        reachedset.add(IntegerAbstractState(20))
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeSepOperator,
                stopContainedOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // 20 covers all other states but is not equal to them, hence the reached set is updated until it converges in the common meaning
        reachedset.asCollection() shouldBe setOf(
            IntegerAbstractState(2),
            IntegerAbstractState(4),
            IntegerAbstractState(6),
            IntegerAbstractState(8),
            IntegerAbstractState(10),
            IntegerAbstractState(20),
        )

        waitlist.clear()
        waitlist.add(IntegerAbstractState(0))
        reachedset.clear()
        reachedset.add(IntegerAbstractState(0))
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeSepOperator,
                stopContainedOperator,
                precisionAdjustment,
                NeverAbortOperator.INSTANCE,
            ),
        ).run(reachedset, waitlist)
        // here, the result coincides with the stopSepOperator because there is no state covering others but unequal to them
        reachedset.asCollection() shouldBe setOf(
            IntegerAbstractState(0),
            IntegerAbstractState(2),
            IntegerAbstractState(4),
            IntegerAbstractState(6),
            IntegerAbstractState(8),
            IntegerAbstractState(10),
        )
    }

    "Abort operator terminates the analysis" {
        val waitlist = DepthFirstWaitlist<IntegerAbstractState>()
        waitlist.add(IntegerAbstractState(0))
        val reachedset = DefaultReachedSet<IntegerAbstractState>()
        val abortOperator = ControllableAbortOperator()
        abortOperator.abort = true
        CpaAlgorithm(
            SimpleCpa(
                transferRelation,
                mergeSepOperator,
                stopContainedOperator,
                precisionAdjustment,
                abortOperator,
            ),
        ).run(reachedset, waitlist)
        // the test should return all states reachable from 0
        reachedset.asCollection() shouldBe setOf()
    }
})
