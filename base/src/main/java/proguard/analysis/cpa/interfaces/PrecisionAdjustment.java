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
import proguard.analysis.cpa.defaults.PrecisionAdjustmentResult;

/**
 * {@link PrecisionAdjustment} allows adjusting the {@link
 * proguard.analysis.cpa.algorithms.CpaAlgorithm} {@link Precision} based of the reached abstract
 * states. The evolution and the interpretation of {@link Precision} are arbitrary.
 */
public interface PrecisionAdjustment {

  /**
   * Returns a new {@link AbstractState} and {@link Precision} for the given reached abstract
   * states.
   */
  <AbstractStateT extends AbstractState<AbstractStateT>>
      PrecisionAdjustmentResult<AbstractStateT> prec(
          AbstractStateT abstractState,
          Precision precision,
          Collection<? extends AbstractStateT> reachedAbstractStates);
}
