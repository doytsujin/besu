/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.flat;

import org.hyperledger.besu.ethereum.api.jsonrpc.internal.processor.TransactionTrace;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.Quantity;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.Trace;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.TracingUtils;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Gas;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.debug.TraceFrame;
import org.hyperledger.besu.ethereum.vm.ExceptionalHaltReason;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.Streams;
import com.google.common.util.concurrent.Atomics;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public class FlatTraceGenerator {

  private static final String ZERO_ADDRESS_STRING = Address.ZERO.toHexString();

  /**
   * Generates a stream of {@link Trace} from the passed {@link TransactionTrace} data.
   *
   * @param transactionTrace the {@link TransactionTrace} to use
   * @param traceCounter the current trace counter value
   * @return a stream of generated traces {@link Trace}
   */
  public static Stream<Trace> generateFromTransactionTrace(
      final TransactionTrace transactionTrace, final AtomicInteger traceCounter) {
    final FlatTrace.Builder firstFlatTraceBuilder = FlatTrace.freshBuilder(transactionTrace);
    final Transaction tx = transactionTrace.getTransaction();

    final Optional<String> smartContractCode =
        tx.getInit().map(__ -> transactionTrace.getResult().getOutput().toString());
    final Optional<String> smartContractAddress =
        smartContractCode.map(
            __ -> Address.contractAddress(tx.getSender(), tx.getNonce()).toHexString());

    // set code field in result node
    smartContractCode.ifPresent(firstFlatTraceBuilder.getResultBuilder()::code);

    // set init field if transaction is a smart contract deployment
    tx.getInit().map(Bytes::toHexString).ifPresent(firstFlatTraceBuilder.getActionBuilder()::init);

    // set to, input and callType fields if not a smart contract
    if (tx.getTo().isPresent()) {
      final Bytes payload = tx.getPayload();
      firstFlatTraceBuilder
          .getActionBuilder()
          .to(tx.getTo().map(Bytes::toHexString).orElse(null))
          .callType("call")
          .input(payload == null ? "0x" : payload.toHexString());
    } else {
      firstFlatTraceBuilder
          .type("create")
          .getResultBuilder()
          .address(smartContractAddress.orElse(null));
    }

    final List<FlatTrace.Builder> flatTraces = new ArrayList<>();

    // stack of previous contexts
    final Deque<FlatTrace.Context> tracesContexts = new ArrayDeque<>();

    // add the first transactionTrace context to the queue of transactionTrace contexts
    FlatTrace.Context currentContext = new FlatTrace.Context(firstFlatTraceBuilder);
    tracesContexts.addLast(currentContext);
    flatTraces.add(currentContext.getBuilder());
    // declare the first transactionTrace context as the previous transactionTrace context
    long cumulativeGasCost = 0;

    int traceFrameIndex = 0;
    final List<TraceFrame> traceFrames = transactionTrace.getTraceFrames();
    for (final TraceFrame traceFrame : traceFrames) {
      cumulativeGasCost += traceFrame.getGasCost().orElse(Gas.ZERO).toLong();
      final String opcodeString = traceFrame.getOpcode();
      if ("CALL".equals(opcodeString)
          || "CALLCODE".equals(opcodeString)
          || "DELEGATECALL".equals(opcodeString)
          || "STATICCALL".equals(opcodeString)) {
        currentContext =
            handleCall(
                transactionTrace,
                traceFrame,
                smartContractAddress,
                flatTraces,
                cumulativeGasCost,
                tracesContexts,
                traceFrameIndex,
                traceFrames,
                opcodeString.toLowerCase(Locale.US));
      } else if ("RETURN".equals(opcodeString) || "STOP".equals(opcodeString)) {
        if (currentContext != null) {
          currentContext.incGasUsed(cumulativeGasCost);
          currentContext =
              handleReturn(transactionTrace, traceFrame, tracesContexts, currentContext);
        }
      } else if ("SELFDESTRUCT".equals(opcodeString)) {
        currentContext.incGasUsed(cumulativeGasCost);
        currentContext = handleSelfDestruct(traceFrame, flatTraces, tracesContexts);
      } else if ("CREATE".equals(opcodeString) || "CREATE2".equals(opcodeString)) {
        currentContext =
            handleCreateOperation(
                transactionTrace,
                smartContractAddress,
                flatTraces,
                tracesContexts,
                cumulativeGasCost,
                traceFrameIndex,
                traceFrames);
      } else if ("REVERT".equals(opcodeString)) {
        currentContext.getBuilder().error(Optional.of("Reverted"));
      } else if (!traceFrame.getExceptionalHaltReasons().isEmpty()) {
        currentContext
            .getBuilder()
            .error(
                traceFrame.getExceptionalHaltReasons().stream()
                    .map(ExceptionalHaltReason::getDescription)
                    .reduce((a, b) -> a + ", " + b));
      }
      traceFrameIndex++;
    }
    return flatTraces.stream().map(FlatTrace.Builder::build);
  }

  private static FlatTrace.Context handleCall(
      final TransactionTrace transactionTrace,
      final TraceFrame traceFrame,
      final Optional<String> smartContractAddress,
      final List<FlatTrace.Builder> flatTraces,
      final long cumulativeGasCost,
      final Deque<FlatTrace.Context> tracesContexts,
      final int traceFrameIndex,
      final List<TraceFrame> traceFrames,
      final String opcodeString) {
    final TraceFrame nextTraceFrame = traceFrames.get(traceFrameIndex + 1);
    final Bytes32[] stack = traceFrame.getStack().orElseThrow();
    final Address contractCallAddress = toAddress(stack[stack.length - 2]);
    final FlatTrace.Context lastContext = tracesContexts.peekLast();
    final String callingAddress = calculateCallingAddress(lastContext);

    if (contractCallAddress.numberOfLeadingZeroBytes() >= 19) {
      // don't log calls to precompiles
      return tracesContexts.peekLast();
    }

    final FlatTrace.Builder subTraceBuilder =
        FlatTrace.builder()
            .traceAddress(calculateTraceAddress(tracesContexts))
            .resultBuilder(Result.builder());
    final Action.Builder subTraceActionBuilder =
        Action.builder()
            .from(smartContractAddress.orElse(callingAddress))
            .to(contractCallAddress.toString())
            .input(
                Optional.ofNullable(nextTraceFrame.getInputData())
                    .map(Bytes::toHexString)
                    .orElse(null))
            .gas(nextTraceFrame.getGasRemaining().toHexString())
            .callType(opcodeString.toLowerCase(Locale.US))
            .value(Quantity.create(transactionTrace.getTransaction().getValue()));

    final FlatTrace.Context currentContext =
        new FlatTrace.Context(subTraceBuilder.actionBuilder(subTraceActionBuilder));
    currentContext.decGasUsed(cumulativeGasCost);
    tracesContexts.addLast(currentContext);
    flatTraces.add(currentContext.getBuilder());
    return currentContext;
  }

  private static FlatTrace.Context handleReturn(
      final TransactionTrace transactionTrace,
      final TraceFrame traceFrame,
      final Deque<FlatTrace.Context> tracesContexts,
      final FlatTrace.Context currentContext) {
    if (tracesContexts.size() == 1) {
      currentContext.setGasUsed(computeGasUsed(transactionTrace, currentContext.getGasUsed()));
    }
    final FlatTrace.Builder traceFrameBuilder = currentContext.getBuilder();
    final Result.Builder resultBuilder = traceFrameBuilder.getResultBuilder();
    final Action.Builder actionBuilder = traceFrameBuilder.getActionBuilder();
    final Bytes outputData = traceFrame.getOutputData();
    if (resultBuilder.getCode() == null) {
      resultBuilder.output(outputData.toHexString());
    }
    // set value for contract creation TXes, CREATE, and CREATE2
    if (actionBuilder.getCallType() == null && traceFrame.getMaybeCode().isPresent()) {
      actionBuilder.init(traceFrame.getMaybeCode().get().getBytes().toHexString());
      resultBuilder.code(outputData.toHexString()).address(traceFrame.getRecipient().toHexString());
      if (currentContext.isCreateOp()) {
        // this is from a CREATE/CREATE2, so add code deposit cost.
        currentContext.incGasUsed(outputData.size() * 200L);
      }
    }
    tracesContexts.removeLast();
    final FlatTrace.Context nextContext = tracesContexts.peekLast();
    if (nextContext != null) {
      nextContext.getBuilder().incSubTraces();
    }
    return nextContext;
  }

  private static FlatTrace.Context handleSelfDestruct(
      final TraceFrame traceFrame,
      final List<FlatTrace.Builder> flatTraces,
      final Deque<FlatTrace.Context> tracesContexts) {
    final Bytes32[] stack = traceFrame.getStack().orElseThrow();
    final Address refundAddress = toAddress(stack[0]);
    final FlatTrace.Builder subTraceBuilder =
        FlatTrace.builder()
            .type("suicide")
            .traceAddress(calculateSelfDescructAddress(tracesContexts));

    final AtomicReference<Wei> weiBalance = Atomics.newReference(Wei.ZERO);
    traceFrame
        .getMaybeRefunds()
        .ifPresent(refunds -> weiBalance.set(refunds.getOrDefault(refundAddress, Wei.ZERO)));

    final Action.Builder callingAction = tracesContexts.peekLast().getBuilder().getActionBuilder();
    final String actionAddress =
        callingAction.getCallType().equals("call")
            ? callingAction.getTo()
            : callingAction.getFrom();
    final Action.Builder subTraceActionBuilder =
        Action.builder()
            .address(actionAddress)
            .refundAddress(refundAddress.toString())
            .balance(TracingUtils.weiAsHex(weiBalance.get()));

    flatTraces.add(
        new FlatTrace.Context(subTraceBuilder.actionBuilder(subTraceActionBuilder)).getBuilder());
    final FlatTrace.Context lastContext = tracesContexts.removeLast();
    lastContext.getBuilder().incSubTraces();
    final FlatTrace.Context currentContext = tracesContexts.peekLast();
    if (currentContext != null) {
      currentContext.getBuilder().incSubTraces();
    }
    return currentContext;
  }

  private static FlatTrace.Context handleCreateOperation(
      final TransactionTrace transactionTrace,
      final Optional<String> smartContractAddress,
      final List<FlatTrace.Builder> flatTraces,
      final Deque<FlatTrace.Context> tracesContexts,
      final long cumulativeGasCost,
      final int traceFrameIndex,
      final List<TraceFrame> traceFrames) {
    final TraceFrame nextTraceFrame = traceFrames.get(traceFrameIndex + 1);
    final FlatTrace.Context lastContext = tracesContexts.peekLast();
    final String callingAddress = calculateCallingAddress(lastContext);

    final FlatTrace.Builder subTraceBuilder =
        FlatTrace.builder()
            .type("create")
            .traceAddress(calculateTraceAddress(tracesContexts))
            .resultBuilder(Result.builder());
    final Action.Builder subTraceActionBuilder =
        Action.builder()
            .from(smartContractAddress.orElse(callingAddress))
            .gas(nextTraceFrame.getGasRemaining().toHexString())
            .value(Quantity.create(transactionTrace.getTransaction().getValue()));

    final FlatTrace.Context currentContext =
        new FlatTrace.Context(subTraceBuilder.actionBuilder(subTraceActionBuilder));
    currentContext.setCreateOp(true);
    currentContext.decGasUsed(cumulativeGasCost);
    tracesContexts.addLast(currentContext);
    flatTraces.add(currentContext.getBuilder());
    return currentContext;
  }

  private static String calculateCallingAddress(final FlatTrace.Context lastContext) {
    if (lastContext.getBuilder().getActionBuilder().getCallType() == null) {
      return ZERO_ADDRESS_STRING;
    }
    switch (lastContext.getBuilder().getActionBuilder().getCallType()) {
      case "call":
      case "staticcall":
        return lastContext.getBuilder().getActionBuilder().getTo();
      case "delegatecall":
      case "callcode":
        return lastContext.getBuilder().getActionBuilder().getFrom();
      default:
        return ZERO_ADDRESS_STRING;
    }
  }

  private static long computeGasUsed(
      final TransactionTrace transactionTrace, final long fallbackValue) {
    final long firstFrameGasRemaining =
        transactionTrace.getTraceFrames().get(0).getGasRemaining().toLong();
    final long gasRemainingAfterTransactionWasProcessed =
        transactionTrace.getResult().getGasRemaining();
    if (firstFrameGasRemaining > gasRemainingAfterTransactionWasProcessed) {
      return firstFrameGasRemaining - gasRemainingAfterTransactionWasProcessed;
    } else {
      return fallbackValue;
    }
  }

  private static Address toAddress(final Bytes32 value) {
    return Address.wrap(
        Bytes.of(Arrays.copyOfRange(value.toArray(), Bytes32.SIZE - Address.SIZE, Bytes32.SIZE)));
  }

  private static List<Integer> calculateTraceAddress(final Deque<FlatTrace.Context> contexts) {
    return contexts.stream()
        .map(context -> context.getBuilder().getSubtraces())
        .collect(Collectors.toList());
  }

  private static List<Integer> calculateSelfDescructAddress(
      final Deque<FlatTrace.Context> contexts) {
    return Streams.concat(
            contexts.stream()
                .map(context -> context.getBuilder().getSubtraces())) // , Stream.of(0))
        .collect(Collectors.toList());
  }
}
