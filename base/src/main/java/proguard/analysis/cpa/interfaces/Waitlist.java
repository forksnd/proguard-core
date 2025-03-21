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

package proguard.analysis.cpa.interfaces;

import java.util.Collection;

/**
 * The {@link Waitlist} stores the {@link AbstractState}s the {@link
 * proguard.analysis.cpa.algorithms.CpaAlgorithm} needs to process.
 *
 * @param <StateT> The states contained in the waitlist.
 */
public interface Waitlist<StateT extends AbstractState<StateT>> {

  /** Adds an abstract state. */
  void add(StateT state);

  /** Adds multiple abstract states. */
  void addAll(Collection<? extends StateT> abstractStates);

  /** Empties the waitlist. */
  void clear();

  /** Checks whether the abstract state is present. */
  boolean contains(StateT abstractState);

  /** Checks whether the waitlist is empty. */
  boolean isEmpty();

  /** Remove the next abstract state and return it. */
  StateT pop();

  /** Removes an abstract state. */
  boolean remove(StateT abstractState);

  /** Removes multiple abstract states. */
  void removeAll(Collection<? extends StateT> abstractStates);

  /** Returns the size of the waitlist. */
  int size();
}
