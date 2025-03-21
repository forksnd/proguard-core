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

package proguard.analysis.cpa.jvm.domain.memory;

import static proguard.exception.ErrorId.ANALYSIS_JVM_MEMORY_LOCATION_TRANSFER_RELATION_TYPE_UNSUPPORTED;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import proguard.analysis.cpa.bam.BamCache;
import proguard.analysis.cpa.bam.BamCpa;
import proguard.analysis.cpa.bam.BlockAbstraction;
import proguard.analysis.cpa.bam.ExpandOperator;
import proguard.analysis.cpa.bam.ReduceOperator;
import proguard.analysis.cpa.defaults.ProgramLocationDependentReachedSet;
import proguard.analysis.cpa.defaults.SetAbstractState;
import proguard.analysis.cpa.interfaces.AbstractState;
import proguard.analysis.cpa.interfaces.CallEdge;
import proguard.analysis.cpa.interfaces.Precision;
import proguard.analysis.cpa.interfaces.TransferRelation;
import proguard.analysis.cpa.jvm.cfa.JvmCfa;
import proguard.analysis.cpa.jvm.cfa.edges.JvmAssumeExceptionCfaEdge;
import proguard.analysis.cpa.jvm.cfa.edges.JvmCallCfaEdge;
import proguard.analysis.cpa.jvm.cfa.edges.JvmCfaEdge;
import proguard.analysis.cpa.jvm.cfa.edges.JvmInstructionCfaEdge;
import proguard.analysis.cpa.jvm.cfa.nodes.JvmCfaNode;
import proguard.analysis.cpa.jvm.cfa.nodes.JvmUnknownCfaNode;
import proguard.analysis.cpa.jvm.domain.memory.JvmMemoryLocationAbstractState.StackEntry;
import proguard.analysis.cpa.jvm.state.JvmAbstractState;
import proguard.analysis.cpa.jvm.util.ConstantLookupVisitor;
import proguard.analysis.cpa.jvm.util.InstructionClassifier;
import proguard.analysis.cpa.jvm.witness.JvmLocalVariableLocation;
import proguard.analysis.cpa.jvm.witness.JvmMemoryLocation;
import proguard.analysis.cpa.jvm.witness.JvmStackLocation;
import proguard.analysis.cpa.jvm.witness.JvmStaticFieldLocation;
import proguard.analysis.datastructure.callgraph.Call;
import proguard.classfile.AccessConstants;
import proguard.classfile.Clazz;
import proguard.classfile.Method;
import proguard.classfile.attribute.CodeAttribute;
import proguard.classfile.instruction.BranchInstruction;
import proguard.classfile.instruction.ConstantInstruction;
import proguard.classfile.instruction.Instruction;
import proguard.classfile.instruction.SimpleInstruction;
import proguard.classfile.instruction.SwitchInstruction;
import proguard.classfile.instruction.VariableInstruction;
import proguard.classfile.instruction.visitor.InstructionVisitor;
import proguard.classfile.util.ClassUtil;
import proguard.exception.ProguardCoreException;

/**
 * The {@link JvmMemoryLocationTransferRelation} computes the backward successors of an {@link
 * JvmMemoryLocationAbstractState} for a given instruction. A backward successor is a memory
 * location which may have contributed to the value of the current {@link JvmMemoryLocation}.
 *
 * <p>The transfer relation uses a {@link BamCache} containing the results of an analysis in order
 * to calculate the successors {@link JvmMemoryLocationAbstractState}:
 *
 * <p>
 *
 * <ul>
 *   <li>If a successor is in the currently analyzed method just use the current {@link
 *       ProgramLocationDependentReachedSet} (representing the results of the back-traced analysis
 *       for the current method call with a specific entry state).
 *   <li>If the current state can be the result of a method call, search for entry in the cache that
 *       can result in the current state (i.e. from the cache entries of the called methods get the
 *       ones that have as initial state the result of the {@link
 *       proguard.analysis.cpa.bam.ReduceOperator} of the back-traced analysis for the caller
 *       abstract state and that have an exit state that results in the current state after applying
 *       the {@link proguard.analysis.cpa.bam.ExpandOperator} of the back-traced analysis).
 *   <li>If the current state is located in the entry node of a method:
 *       <ul>
 *         <li>If the call site was analyzed during the backward analysis the successor location
 *             will be the known caller.
 *         <li>Otherwise look for all potential callers in the cache (i.e. states that call the
 *             method and result in the current method after applying the {@link
 *             proguard.analysis.cpa.bam.ReduceOperator}).
 *       </ul>
 * </ul>
 *
 * <p>The value of the successor memory location is guaranteed to be greater than the threshold
 * (e.g. if {@link ContentT} is a {@link proguard.analysis.cpa.defaults.SetAbstractState} we can set
 * the threshold to {@link proguard.analysis.cpa.defaults.SetAbstractState#bottom} to guarantee we
 * don't calculate a successor if the taint is not propagated anymore). Thus, the threshold defines
 * the cut-off of the traces generated with {@link JvmMemoryLocationTransferRelation}.
 *
 * @param <ContentT> The content of the jvm states for the traced analysis. For example, this can be
 *     a {@link SetAbstractState} of taints for taint analysis or a {@link
 *     proguard.analysis.cpa.jvm.domain.value.ValueAbstractState} for value analysis.
 */
public class JvmMemoryLocationTransferRelation<ContentT extends AbstractState<ContentT>>
    implements TransferRelation<JvmMemoryLocationAbstractState<ContentT>> {

  private static final Logger log = LogManager.getLogger(JvmMemoryLocationTransferRelation.class);
  private final ContentT threshold;
  private final JvmCfa cfa;
  private final BamCache<ContentT> cache;
  private final ReduceOperator<ContentT> tracedCpaReduceOperator;
  private final ExpandOperator<ContentT> tracedCpaExpandOperator;
  /**
   * Maps calls to locations which should become tainted after the call is invoked. For example, it
   * maps constructor calls to the locations in local variables array or on stack, to which the
   * reference was put after duplicating the uninitialized instance.
   */
  private final Map<Call, Set<JvmMemoryLocation>> extraTaintPropagationLocations;

  /**
   * Create a memory location transfer relation.
   *
   * @param threshold a cut-off threshold
   * @param bamCpa the BAM cpa that was used to calculate the results in the cache
   */
  public JvmMemoryLocationTransferRelation(
      ContentT threshold,
      BamCpa<ContentT> bamCpa,
      Map<Call, Set<JvmMemoryLocation>> extraTaintPropagationLocations) {
    this.threshold = threshold;
    this.cfa = bamCpa.getCfa();
    this.cache = bamCpa.getCache();
    this.tracedCpaReduceOperator = bamCpa.getReduceOperator();
    this.tracedCpaExpandOperator = bamCpa.getExpandOperator();
    this.extraTaintPropagationLocations = extraTaintPropagationLocations;
  }

  // implementations for TransferRelation

  @Override
  public Collection<JvmMemoryLocationAbstractState<ContentT>> generateAbstractSuccessors(
      JvmMemoryLocationAbstractState<ContentT> abstractState, Precision precision) {

    JvmMemoryLocationAbstractState<ContentT> state = abstractState.copy();

    JvmCfaNode programLocation = state.getProgramLocation();
    Collection<JvmCfaEdge> intraproceduralEdges = programLocation.getEnteringIntraproceduralEdges();

    List<JvmMemoryLocationAbstractState<ContentT>> successors = new ArrayList<>();

    if (!intraproceduralEdges.isEmpty()) // backtrace instructions and method calls
    {
      for (JvmCfaEdge edge : intraproceduralEdges) {

        JvmCfaNode intraproceduralParentNode = edge.getSource();

        Optional<JvmAbstractState<ContentT>> intraproceduralParentState =
            getAnalysisAbstractState(state.getSourceReachedSet(), intraproceduralParentNode);
        if (!intraproceduralParentState.isPresent()) {
          continue;
        }

        // backtrace call return

        Collection<JvmCallCfaEdge> interproceduralCallEdges =
            intraproceduralParentNode.getKnownMethodCallEdges();

        /*
        This variable keeps track if, in the case the current node is the result of a function call, the intraprocedural instruction edge should be followed.

        It starts as false only if the code of all potential called methods is known, and can then just be turned to true in case:
            - any called method was not found in the cache, meaning the analysis is incomplete and the intraprocedural edge was followed by BAM (e.g. in case of max stack size reached)
            - no interprocedural successor was found, meaning that the method call had no influence on the memory location
         */
        boolean shouldAnalyzeIntraproceduralCallEdge =
            intraproceduralParentNode.getLeavingEdges().stream()
                .anyMatch(
                    e -> e instanceof JvmCallCfaEdge && e.getTarget() instanceof JvmUnknownCfaNode);
        boolean interproceduralSuccessorFound = false;
        if (!(state.getLocationDependentMemoryLocation().getMemoryLocation()
            instanceof JvmLocalVariableLocation)) // local variables aren't preserved across methods
        {
          JvmAbstractState<ContentT> currentState =
              getAnalysisAbstractState(state.getSourceReachedSet(), programLocation).get();

          for (JvmCallCfaEdge callEdge :
              interproceduralCallEdges) // there can be multiple call edges (e.g. if the called
          // object has multiple potential types)
          {
            if (state.callStackContains(
                callEdge
                    .getTarget()
                    .getSignature())) // skip the interprocedural call in case of recursion
            // TODO handle recursion
            {
              shouldAnalyzeIntraproceduralCallEdge = true;
              continue;
            }

            // apply the BAM reduce operator to find the entry state of the callee and retrieve its
            // block abstraction from the cache
            JvmAbstractState<ContentT> reducedEntryState =
                tracedCpaReduceOperator.reduce(
                    intraproceduralParentState.get(), callEdge.getTarget(), callEdge.getCall());
            BlockAbstraction<ContentT> calleeAbstraction =
                cache.get(reducedEntryState, precision, callEdge.getTarget().getSignature());
            if (calleeAbstraction != null) {
              if (state.getLocationDependentMemoryLocation().getMemoryLocation()
                  instanceof JvmStackLocation) {
                JvmStackLocation stackLocation =
                    (JvmStackLocation)
                        state.getLocationDependentMemoryLocation().getMemoryLocation();
                int index = stackLocation.getIndex();
                if (index
                    > ClassUtil.internalTypeSize(
                        callEdge.getTarget().getSignature().descriptor.returnType)) {
                  continue;
                }
              }

              JvmCfaNode calleeExitNode =
                  cfa.getFunctionReturnExitNode(
                      callEdge.getTarget().getSignature(), callEdge.getTarget().getClazz());
              Optional<JvmAbstractState<ContentT>> returnState =
                  getAnalysisAbstractState(calleeAbstraction.getReachedSet(), calleeExitNode);

              if (!returnState.isPresent()) {
                shouldAnalyzeIntraproceduralCallEdge = true;
                continue;
              }
              ContentT value =
                  state
                      .getLocationDependentMemoryLocation()
                      .getMemoryLocation()
                      .extractValueOrDefault(returnState.get(), threshold);

              if (value.isLessOrEqual(threshold)) // end of trace
              {
                continue;
              }

              // apply the expand operator to the exit node of the callee to check if it resulted in
              // the current state
              if (tracedCpaExpandOperator
                  .expand(
                      intraproceduralParentState.get(),
                      returnState.get(),
                      callEdge.getTarget(),
                      callEdge.getCall())
                  .isLessOrEqual(currentState)) {
                LinkedList<StackEntry<ContentT>> callStack = state.copyStack();
                callStack.push(
                    new StackEntry<>(
                        calleeExitNode.getSignature(),
                        state.getSourceReachedSet(),
                        intraproceduralParentState.get()));
                JvmMemoryLocation successorLocation =
                    state.getLocationDependentMemoryLocation().getMemoryLocation();
                successors.add(
                    new JvmMemoryLocationAbstractState<>(
                        successorLocation,
                        calleeExitNode,
                        calleeAbstraction.getReachedSet(),
                        callStack));
                interproceduralSuccessorFound = true;
              }
            } else {
              // if the call does not have a valid BAM cache entry, it means it was analyzed
              // intra-procedurally (either because of max call stack reached,
              // no code available for the called method, or abort condition)
              shouldAnalyzeIntraproceduralCallEdge = true;
            }
          }
        }

        if (!interproceduralSuccessorFound) {
          shouldAnalyzeIntraproceduralCallEdge = true;
        }

        // backtrace the intraprocedural instruction edge (unless the trace goes only
        // interprocedurally)
        List<JvmMemoryLocation> intraproceduralSuccessorMemoryLocations = new ArrayList<>();

        if (edge instanceof JvmInstructionCfaEdge
            && (!InstructionClassifier.isInvoke(
                    ((JvmInstructionCfaEdge) edge).getInstruction().opcode)
                || shouldAnalyzeIntraproceduralCallEdge)) {
          intraproceduralSuccessorMemoryLocations.addAll(
              getSuccessorMemoryLocationsForInstruction(
                  state,
                  intraproceduralParentState.get(),
                  ((JvmInstructionCfaEdge) edge).getInstruction(),
                  state.getProgramLocation().getClazz(),
                  state.getProgramLocation().getSignature().getReferencedMethod(),
                  state.getProgramLocation().getOffset()));
        } else if (edge
            instanceof JvmAssumeExceptionCfaEdge) // the catch edge preserves the memory location
        {
          intraproceduralSuccessorMemoryLocations.add(
              state.getLocationDependentMemoryLocation().getMemoryLocation());
        }

        successors.addAll(
            getSuccessorsFromMemoryLocations(
                intraproceduralSuccessorMemoryLocations,
                intraproceduralParentState.get(),
                state.getSourceReachedSet(),
                state.copyStack()));
      }
    }

    if (programLocation.isEntryNode()) // backtrace from entry node to caller
    {
      StackEntry<ContentT> currentEntry = state.peekCallStack();
      Optional<JvmMemoryLocation> callerMemoryLocation = createCallerLocation(state);

      if (callerMemoryLocation.isPresent()) {
        if (currentEntry == null) // caller unknown
        {
          // the tracked analysis' entry state of the currently analyzed method
          Optional<JvmAbstractState<ContentT>> calledState =
              getAnalysisAbstractState(state.getSourceReachedSet(), programLocation);
          if (!calledState.isPresent()) {
            return Collections.emptyList();
          }

          programLocation.getEnteringEdges().stream()
              .filter(CallEdge.class::isInstance)
              .map(
                  callEdge ->
                      getCallersInfo((JvmCallCfaEdge) callEdge, programLocation, calledState.get()))
              .flatMap(Collection::stream)
              .forEach(
                  callerInfo ->
                      getSuccessorsFromMemoryLocations(
                              Collections.singletonList(callerMemoryLocation.get()),
                              callerInfo.state,
                              callerInfo.reachedSet,
                              new LinkedList<>())
                          .stream()
                          .findFirst()
                          .ifPresent(successors::add));
        } else // entered method call while backtracking another method, the caller is known
        {
          LinkedList<StackEntry<ContentT>> successorStack = state.copyStack();
          successorStack.pop();
          successors.addAll(
              getSuccessorsFromMemoryLocations(
                  Collections.singletonList(callerMemoryLocation.get()),
                  currentEntry.callerState,
                  currentEntry.reachedSet,
                  successorStack));
        }
      }
    }

    successors.forEach(
        successor ->
            abstractState.addSourceLocation(
                // This has side effects on the entry abstract state
                successor.getLocationDependentMemoryLocation()));
    return successors;
  }

  /**
   * The default implementation traces the return value back to the method arguments and the
   * instance. Additionally, handles extra taints' propagation if the need is identified at this
   * call site.
   */
  protected List<JvmMemoryLocation> processCall(
      JvmMemoryLocation memoryLocation,
      ConstantInstruction callInstruction,
      Clazz clazz,
      JvmCfaNode parentNode) {
    List<JvmMemoryLocation> successors =
        new ArrayList<>(backtraceStackLocation(memoryLocation, callInstruction, clazz));
    successors.addAll(handleExtraPropagation(memoryLocation, callInstruction, clazz, parentNode));
    return successors;
  }

  /**
   * Returns extra locations where the taints should be propagated via the leaving edges at the
   * given call site, based on the information from {@link
   * JvmMemoryLocationTransferRelation#extraTaintPropagationLocations}.
   */
  private Collection<? extends JvmMemoryLocation> handleExtraPropagation(
      JvmMemoryLocation memoryLocation,
      ConstantInstruction callInstruction,
      Clazz clazz,
      JvmCfaNode parentNode) {
    if (parentNode.getLeavingEdges().stream()
        .noneMatch(edge -> shouldEdgePropagateTaint(edge, memoryLocation))) {
      return Collections.emptyList();
    } else {
      return getPoppedLocations(callInstruction.stackPopCount(clazz));
    }
  }

  /**
   * Returns 'true' for the calls that require extra handling of taint propagation at the given jvm
   * memory location. Information about such calls and corresponding locations is contained in
   * {@link JvmMemoryLocationTransferRelation#extraTaintPropagationLocations}.
   */
  private boolean shouldEdgePropagateTaint(
      JvmCfaEdge jvmCfaEdge, JvmMemoryLocation memoryLocation) {
    if (!(jvmCfaEdge instanceof JvmCallCfaEdge)) {
      return false;
    }
    Call call = ((JvmCallCfaEdge) jvmCfaEdge).getCall();
    return (extraTaintPropagationLocations.containsKey(call)
        && extraTaintPropagationLocations.get(call).contains(memoryLocation));
  }

  /**
   * Gets from the BAM cache the abstract state of the analysis that's being traced for a certain
   * location. Will return an empty optional if the state is not found.
   */
  private Optional<JvmAbstractState<ContentT>> getAnalysisAbstractState(
      ProgramLocationDependentReachedSet<JvmAbstractState<ContentT>> reachedSet,
      JvmCfaNode location) {
    Collection<JvmAbstractState<ContentT>> states = reachedSet.getReached(location);
    if (states.isEmpty()) {
      // this is not necessarily an error, for example might happen if the analysis being traced was
      // not completed
      log.info(
          String.format(
              "Missing entry state in the cache for method %s at offset %d",
              location.getSignature().getFqn(), location.getOffset()));
      return Optional.empty();
    }
    return states.stream().findFirst();
  }

  private List<JvmMemoryLocation> getSuccessorMemoryLocationsForInstruction(
      JvmMemoryLocationAbstractState<ContentT> abstractState,
      JvmAbstractState<ContentT> parentState,
      Instruction instruction,
      Clazz clazz,
      Method method,
      int offset) {
    List<JvmMemoryLocation> answer = new ArrayList<>();
    instruction.accept(
        clazz,
        method,
        null,
        offset,
        new InstructionAbstractInterpreter(
            answer,
            abstractState.getLocationDependentMemoryLocation().getMemoryLocation(),
            parentState));
    return answer;
  }

  /**
   * Returns all the potential callers of an entry abstract states found in the cache (i.e. for each
   * cache entry of the caller method add the state to the return collection if it reduces into the
   * called abstract state).
   */
  private Collection<CallerInfo<ContentT>> getCallersInfo(
      JvmCallCfaEdge callEdge, JvmCfaNode calledLocation, JvmAbstractState<ContentT> calledState) {
    JvmCfaNode callerLocation = callEdge.getSource();
    Collection<BlockAbstraction<ContentT>> callerCacheEntries =
        cache.get(callerLocation.getSignature());

    List<CallerInfo<ContentT>> callerInfos = new ArrayList<>();

    for (BlockAbstraction<ContentT> cacheEntry : callerCacheEntries) {
      Optional<JvmAbstractState<ContentT>> callerState =
          cacheEntry.getReachedSet().getReached(callerLocation).stream().findFirst();

      if (callerState.isPresent()
          && tracedCpaReduceOperator
              .reduce(callerState.get(), calledLocation, callEdge.getCall())
              .equals(calledState)) {
        callerInfos.add(new CallerInfo<>(cacheEntry.getReachedSet(), callerState.get()));
      }
    }

    return callerInfos;
  }

  private static class CallerInfo<ContentT extends AbstractState<ContentT>> {
    public final ProgramLocationDependentReachedSet<JvmAbstractState<ContentT>> reachedSet;
    public final JvmAbstractState<ContentT> state;

    public CallerInfo(
        ProgramLocationDependentReachedSet<JvmAbstractState<ContentT>> reachedSet,
        JvmAbstractState<ContentT> state) {
      this.reachedSet = reachedSet;
      this.state = state;
    }
  }

  /** Returns the stack location before a method is called. */
  private Optional<JvmMemoryLocation> createCallerLocation(
      JvmMemoryLocationAbstractState<ContentT> currentState) {
    JvmMemoryLocation memoryLocation =
        currentState.getLocationDependentMemoryLocation().getMemoryLocation();

    if (memoryLocation instanceof JvmLocalVariableLocation) {
      JvmLocalVariableLocation argumentLocation = (JvmLocalVariableLocation) memoryLocation;
      String currentDescriptor =
          currentState.getProgramLocation().getSignature().descriptor.toString();
      boolean isStatic =
          (currentState
                      .getProgramLocation()
                      .getClazz()
                      .findMethod(
                          currentState.getProgramLocation().getSignature().method,
                          currentDescriptor)
                      .getAccessFlags()
                  & AccessConstants.STATIC)
              != 0;
      int parameterNumber =
          ClassUtil.internalMethodParameterNumber(
              currentDescriptor, isStatic, argumentLocation.index);
      int parameterIndex = isStatic || parameterNumber == 0 ? parameterNumber : parameterNumber - 1;
      int parameterSize = ClassUtil.internalMethodParameterSize(currentDescriptor, isStatic);
      String internalType =
          ClassUtil.internalMethodParameterType(currentDescriptor, parameterIndex);
      boolean isCategory2 = ClassUtil.isInternalCategory2Type(internalType);

      return Optional.of(
          new JvmStackLocation(parameterSize - argumentLocation.index - (isCategory2 ? 2 : 1)));
    } else if (memoryLocation instanceof JvmStaticFieldLocation) {
      return Optional.of(memoryLocation);
    } else if (memoryLocation instanceof JvmStackLocation) {
      return Optional.empty();
    } else {
      throw new ProguardCoreException.Builder(
              "Unsupported memory location type %s",
              ANALYSIS_JVM_MEMORY_LOCATION_TRANSFER_RELATION_TYPE_UNSUPPORTED)
          .errorParameters(memoryLocation.getClass().getCanonicalName())
          .build();
    }
  }

  /** Returns a successor state for each successor memory location above the threshold. */
  private Collection<JvmMemoryLocationAbstractState<ContentT>> getSuccessorsFromMemoryLocations(
      List<JvmMemoryLocation> successorMemoryLocations,
      JvmAbstractState<ContentT> parentState,
      ProgramLocationDependentReachedSet<JvmAbstractState<ContentT>> successorsReachedSet,
      LinkedList<StackEntry<ContentT>> callStack) {
    List<JvmMemoryLocation> resultMemoryLocations = new ArrayList<>();
    for (JvmMemoryLocation location : successorMemoryLocations) {
      if (!location.extractValueOrDefault(parentState, threshold).isLessOrEqual(threshold)) {
        resultMemoryLocations.add(location);
      }
    }
    return resultMemoryLocations.stream()
        .map(
            location ->
                new JvmMemoryLocationAbstractState<>(
                    location, parentState.getProgramLocation(), successorsReachedSet, callStack))
        .collect(Collectors.toList());
  }

  private List<JvmMemoryLocation> backtraceStackLocation(
      JvmMemoryLocation memoryLocation, Instruction instruction, Clazz clazz) {
    List<JvmMemoryLocation> result = new ArrayList<>();
    if (!(memoryLocation instanceof JvmStackLocation)) {
      result.add(memoryLocation);
      return result;
    }
    int index = ((JvmStackLocation) memoryLocation).getIndex();
    int pushCount = instruction.stackPushCount(clazz);
    int popCount = instruction.stackPopCount(clazz);
    if (index >= pushCount) {
      result.add(new JvmStackLocation(index - pushCount + popCount));
      return result;
    }
    result.addAll(getPoppedLocations(popCount));
    return result;
  }

  private List<JvmMemoryLocation> getPoppedLocations(int popCount) {
    List<JvmMemoryLocation> result = new ArrayList<>();
    for (int i = 0; i < popCount; ++i) {
      result.add(new JvmStackLocation(i));
    }
    return result;
  }

  /**
   * This {@link InstructionVisitor} performs generic operations (e.g., loads, stores) parametrized
   * by the specific behavior of {@link JvmMemoryLocationTransferRelation} for instruction
   * applications, method invocations, and constructing literals.
   */
  private class InstructionAbstractInterpreter implements InstructionVisitor {

    private final List<JvmMemoryLocation> answer;
    private final JvmMemoryLocation memoryLocation;
    private final JvmAbstractState<ContentT> parentState;
    private final ConstantLookupVisitor constantLookupVisitor = new ConstantLookupVisitor();

    public InstructionAbstractInterpreter(
        List<JvmMemoryLocation> answer,
        JvmMemoryLocation memoryLocation,
        JvmAbstractState<ContentT> parentState) {
      this.answer = answer;
      this.memoryLocation = memoryLocation;
      this.parentState = parentState;
    }

    // implementations for InstructionVisitor

    @Override
    public void visitSimpleInstruction(
        Clazz clazz,
        Method method,
        CodeAttribute codeAttribute,
        int offset,
        SimpleInstruction simpleInstruction) {
      if (!(memoryLocation instanceof JvmStackLocation)
          || InstructionClassifier.isReturn(simpleInstruction.opcode)) {
        // non-stack locations aren't affected by simple instructions
        answer.add(memoryLocation);
        return;
      }
      int index = ((JvmStackLocation) memoryLocation).getIndex();
      if (isStackLocationTooDeep((JvmStackLocation) memoryLocation, simpleInstruction, clazz)) {
        // if the location is too deep in the stack, offset the location by the instruction pop/push
        // difference
        answer.addAll(backtraceStackLocation(memoryLocation, simpleInstruction, clazz));
        return;
      }
      switch (simpleInstruction.opcode) {
        case Instruction.OP_DUP:
          answer.add(new JvmStackLocation(0));
          break;
        case Instruction.OP_DUP_X1:
          {
            answer.add(index == 2 ? new JvmStackLocation(0) : memoryLocation);
            break;
          }
        case Instruction.OP_DUP_X2:
          {
            answer.add(index == 3 ? new JvmStackLocation(0) : memoryLocation);
            break;
          }
        case Instruction.OP_DUP2:
          {
            answer.add(index > 1 ? new JvmStackLocation(index - 2) : memoryLocation);
            break;
          }
        case Instruction.OP_DUP2_X1:
          {
            answer.add(index > 2 ? new JvmStackLocation(index - 3) : memoryLocation);
            break;
          }
        case Instruction.OP_DUP2_X2:
          {
            answer.add(index > 3 ? new JvmStackLocation(index - 4) : memoryLocation);
            break;
          }
        case Instruction.OP_SWAP:
          {
            answer.add(new JvmStackLocation(1 - index));
            break;
          }
        case Instruction.OP_IALOAD:
        case Instruction.OP_FALOAD:
        case Instruction.OP_AALOAD:
        case Instruction.OP_BALOAD:
        case Instruction.OP_CALOAD:
        case Instruction.OP_SALOAD:
        case Instruction.OP_LALOAD:
        case Instruction.OP_DALOAD:
          {
            answer.add(new JvmStackLocation(0));
            answer.add(new JvmStackLocation(1));
            break;
          }
        default: // arithmetic and literal instructions
          {
            answer.addAll(backtraceStackLocation(memoryLocation, simpleInstruction, clazz));
          }
      }
    }

    @Override
    public void visitVariableInstruction(
        Clazz clazz,
        Method method,
        CodeAttribute codeAttribute,
        int offset,
        VariableInstruction variableInstruction) {
      if (variableInstruction.opcode == Instruction.OP_IINC) {
        // increment does not affect the memory location
        answer.add(memoryLocation);
        return;
      }
      if (variableInstruction.isLoad()) {
        answer.addAll(backtraceStackLocation(memoryLocation, variableInstruction, clazz));
        if (memoryLocation instanceof JvmStackLocation
            && !isStackLocationTooDeep(
                (JvmStackLocation) memoryLocation, variableInstruction, clazz)) {
          // the loaded stack location maps to the corresponding local variable array cell
          answer.add(
              new JvmLocalVariableLocation(
                  variableInstruction.variableIndex + ((JvmStackLocation) memoryLocation).index));
        }
        return;
      }
      if (!(memoryLocation instanceof JvmLocalVariableLocation)
          || ((JvmLocalVariableLocation) memoryLocation).index
              != variableInstruction.variableIndex) {
        // stores affect the corresponding variable array entry only
        answer.add(memoryLocation);
        return;
      }
      answer.add(new JvmStackLocation(0));
      if (variableInstruction.isCategory2()) {
        answer.add(new JvmStackLocation(1));
      }
    }

    @Override
    public void visitConstantInstruction(
        Clazz clazz,
        Method method,
        CodeAttribute codeAttribute,
        int offset,
        ConstantInstruction constantInstruction) {
      switch (constantInstruction.opcode) {
        case Instruction.OP_GETSTATIC:
          {
            constantLookupVisitor.isStatic = true;
            clazz.constantPoolEntryAccept(constantInstruction.constantIndex, constantLookupVisitor);
            answer.addAll(backtraceStackLocation(memoryLocation, constantInstruction, clazz));
            if (memoryLocation instanceof JvmStackLocation
                && !isStackLocationTooDeep(
                    (JvmStackLocation) memoryLocation, constantInstruction, clazz)) {
              answer.add(new JvmStaticFieldLocation(constantLookupVisitor.result));
            }
            break;
          }
        case Instruction.OP_PUTSTATIC:
          constantLookupVisitor.isStatic = true;
          clazz.constantPoolEntryAccept(constantInstruction.constantIndex, constantLookupVisitor);
          if (memoryLocation instanceof JvmStackLocation) {
            answer.add(
                new JvmStackLocation(
                    ((JvmStackLocation) memoryLocation).getIndex()
                        + constantLookupVisitor.resultSize));
            break;
          }
          if (memoryLocation instanceof JvmStaticFieldLocation) {
            if (constantLookupVisitor.result.equals(
                ((JvmStaticFieldLocation) memoryLocation).fqn)) {
              answer.add(new JvmStackLocation(0));
              if (constantLookupVisitor.resultSize == 2) {
                answer.add(new JvmStackLocation(1));
              }
            } else {
              answer.add(memoryLocation);
            }
            break;
          }
          answer.add(memoryLocation);
          break;
        case Instruction.OP_GETFIELD:
          {
            constantLookupVisitor.isStatic = false;
            clazz.constantPoolEntryAccept(constantInstruction.constantIndex, constantLookupVisitor);
            answer.addAll(backtraceStackLocation(memoryLocation, constantInstruction, clazz));
            break;
          }
        case Instruction.OP_PUTFIELD:
          {
            constantLookupVisitor.isStatic = false;
            clazz.constantPoolEntryAccept(constantInstruction.constantIndex, constantLookupVisitor);
            if (memoryLocation instanceof JvmStackLocation) {
              answer.add(
                  new JvmStackLocation(
                      ((JvmStackLocation) memoryLocation).index
                          + constantLookupVisitor.resultSize
                          + 1));
              break;
            }
            answer.add(memoryLocation);
            break;
          }
        case Instruction.OP_INVOKESTATIC:
        case Instruction.OP_INVOKEDYNAMIC:
        case Instruction.OP_INVOKEVIRTUAL:
        case Instruction.OP_INVOKESPECIAL:
        case Instruction.OP_INVOKEINTERFACE:
          answer.addAll(
              processCall(
                  memoryLocation, constantInstruction, clazz, parentState.getProgramLocation()));
          break;
        case Instruction.OP_NEW:
        case Instruction.OP_NEWARRAY:
        case Instruction.OP_ANEWARRAY:
        case Instruction.OP_MULTIANEWARRAY:
        default:
          answer.addAll(backtraceStackLocation(memoryLocation, constantInstruction, clazz));
      }
    }

    @Override
    public void visitBranchInstruction(
        Clazz clazz,
        Method method,
        CodeAttribute codeAttribute,
        int offset,
        BranchInstruction branchInstruction) {
      answer.addAll(backtraceStackLocation(memoryLocation, branchInstruction, clazz));
    }

    @Override
    public void visitAnySwitchInstruction(
        Clazz clazz,
        Method method,
        CodeAttribute codeAttribute,
        int offset,
        SwitchInstruction switchInstruction) {
      answer.addAll(backtraceStackLocation(memoryLocation, switchInstruction, clazz));
    }

    private boolean isStackLocationTooDeep(
        JvmStackLocation stackLocation, Instruction instruction, Clazz clazz) {
      return stackLocation.index >= instruction.stackPushCount(clazz);
    }
  }
}
