/*
 * ProGuardCORE -- library to process Java bytecode.
 *
 * Copyright (c) 2002-2023 Guardsquare NV
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

package proguard.exception;

/** Class holding all the error ids for exceptions occurring in the program. */
public final class ErrorId {
  // this id is for testing purposes, to be removed at the end of T20409
  public static int TEST_ID = 99;

  // Partial evaluator exceptions: Range 1000 - 2000
  public static final int VARIABLE_EMPTY_SLOT = 1_000;
  public static final int VARIABLE_INDEX_OUT_OF_BOUND = 1_001;
  public static final int VARIABLE_TYPE = 1_002;
  public static final int EXCESSIVE_COMPLEXITY = 1_003;
  public static final int INCOMPLETE_CLASS_HIERARCHY = 1_004;
  public static final int EMPTY_CODE_ATTRIBUTE = 1_005;
  public static final int STACK_TYPE = 1_006;
  public static final int STACK_CATEGORY_ONE = 1_007;
  public static final int ARRAY_INDEX_OUT_OF_BOUND = 1_008;
  public static final int EXPECTED_ARRAY = 1_009;
  public static final int VARIABLE_GENERALIZATION = 1_010;
  public static final int STACK_GENERALIZATION = 1_011;
  public static final int ARRAY_STORE_TYPE_EXCEPTION = 1_012;
  public static final int PARTIAL_EVALUATOR_ERROR1 = 1_098;
  public static final int PARTIAL_EVALUATOR_ERROR2 = 1_099;
  public static final int NEGATIVE_STACK_SIZE = 2_000;

  public static final int MAX_STACK_SIZE_COMPUTER_ERROR = 3_000;

  public static final int CLASS_REFERENCE_FIXER_ERROR = 4_000;

  public static final int CODE_ATTRIBUTE_EDITOR_ERROR = 5_000;

  public static final int CODE_PREVERIFIER_ERROR = 6_000;

  public static final int CODE_SUBROUTINE_INLINER_ERROR = 7_000;

  // Proguard Util Exceptions
  public static final int WILDCARD_WRONG_INDEX = 8_000;

  /** Private constructor to prevent instantiation of the class. */
  private ErrorId() {}
}
