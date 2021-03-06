/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.lir.alloc.trace;

import static com.oracle.graal.lir.alloc.trace.TraceBuilderPhase.TRACE_DUMP_LEVEL;
import static com.oracle.graal.lir.alloc.trace.TraceUtil.isTrivialTrace;

import java.util.List;

import com.oracle.graal.compiler.common.alloc.RegisterAllocationConfig;
import com.oracle.graal.compiler.common.alloc.Trace;
import com.oracle.graal.compiler.common.alloc.TraceBuilderResult;
import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.DebugCounter;
import com.oracle.graal.debug.Indent;
import com.oracle.graal.lir.LIR;
import com.oracle.graal.lir.LIRInstruction;
import com.oracle.graal.lir.alloc.trace.TraceAllocationPhase.TraceAllocationContext;
import com.oracle.graal.lir.alloc.trace.lsra.IntervalData;
import com.oracle.graal.lir.alloc.trace.lsra.TraceIntervalMap;
import com.oracle.graal.lir.alloc.trace.lsra.TraceLinearScan;
import com.oracle.graal.lir.gen.LIRGenerationResult;
import com.oracle.graal.lir.gen.LIRGeneratorTool.MoveFactory;
import com.oracle.graal.lir.phases.AllocationPhase;
import com.oracle.graal.lir.phases.AllocationStage;
import com.oracle.graal.lir.ssi.SSIUtil;
import com.oracle.graal.lir.ssi.SSIVerifier;
import com.oracle.graal.options.Option;
import com.oracle.graal.options.OptionType;
import com.oracle.graal.options.StableOptionValue;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.AllocatableValue;

/**
 * An implementation of a Trace Register Allocator as described in
 * <a href="http://dx.doi.org/10.1145/2814189.2814199">"Trace Register Allocation"</a> by Josef
 * Eisl.
 */
public final class TraceRegisterAllocationPhase extends AllocationPhase {

    public static class Options {
        // @formatter:off
        @Option(help = "Use inter-trace register hints.", type = OptionType.Debug)
        public static final StableOptionValue<Boolean> TraceRAuseInterTraceHints = new StableOptionValue<>(true);
        @Option(help = "Use special allocator for trivial blocks.", type = OptionType.Debug)
        public static final StableOptionValue<Boolean> TraceRAtrivialBlockAllocator = new StableOptionValue<>(true);
        @Option(help = "Share information about spilled values to other traces.", type = OptionType.Debug)
        public static final StableOptionValue<Boolean> TraceRAshareSpillInformation = new StableOptionValue<>(true);
        @Option(help = "Reuse spill slots for global move resolution cycle breaking.", type = OptionType.Debug)
        public static final StableOptionValue<Boolean> TraceRAreuseStackSlotsForMoveResolutionCycleBreaking = new StableOptionValue<>(true);
        @Option(help = "Cache stack slots globally (i.e. a variable always gets the same slot in every trace).", type = OptionType.Debug)
        public static final StableOptionValue<Boolean> TraceRACacheStackSlots = new StableOptionValue<>(true);
        // @formatter:on
    }

    private static final TraceGlobalMoveResolutionPhase TRACE_GLOBAL_MOVE_RESOLUTION_PHASE = new TraceGlobalMoveResolutionPhase();
    private static final TraceTrivialAllocator TRACE_TRIVIAL_ALLOCATOR = new TraceTrivialAllocator();

    private static final DebugCounter trivialTracesCounter = Debug.counter("TraceRA[trivialTraces]");
    private static final DebugCounter tracesCounter = Debug.counter("TraceRA[traces]");

    @Override
    @SuppressWarnings("try")
    protected <B extends AbstractBlockBase<B>> void run(TargetDescription target, LIRGenerationResult lirGenRes, List<B> codeEmittingOrder, List<B> linearScanOrder, AllocationContext context) {
        MoveFactory spillMoveFactory = context.spillMoveFactory;
        RegisterAllocationConfig registerAllocationConfig = context.registerAllocationConfig;
        LIR lir = lirGenRes.getLIR();
        assert SSIVerifier.verify(lir) : "LIR not in SSI form.";
        TraceBuilderResult<B> resultTraces = getTraces(context);

        TraceAllocationContext traceContext = new TraceAllocationContext(spillMoveFactory, registerAllocationConfig, resultTraces);
        AllocatableValue[] cachedStackSlots = Options.TraceRACacheStackSlots.getValue() ? new AllocatableValue[lir.numVariables()] : null;

        Debug.dump(Debug.INFO_LOG_LEVEL, lir, "Before TraceRegisterAllocation");
        TraceIntervalMap intervalMap = getIntervalMap(context);
        try (Scope s0 = Debug.scope("AllocateTraces", resultTraces)) {
            for (Trace<B> trace : resultTraces.getTraces()) {
                try (Indent i = Debug.logAndIndent("Allocating Trace%d: %s", trace.getId(), trace); Scope s = Debug.scope("AllocateTrace", trace)) {
                    tracesCounter.increment();
                    if (trivialTracesCounter.isEnabled() && isTrivialTrace(lir, trace)) {
                        trivialTracesCounter.increment();
                    }
                    Debug.dump(TRACE_DUMP_LEVEL, trace, "Trace%s: %s", trace.getId(), trace);
                    if (Options.TraceRAtrivialBlockAllocator.getValue() && isTrivialTrace(lir, trace)) {
                        TRACE_TRIVIAL_ALLOCATOR.apply(target, lirGenRes, codeEmittingOrder, trace, traceContext, false);
                    } else {
                        TraceLinearScan allocator = new TraceLinearScan(target, lirGenRes, spillMoveFactory, registerAllocationConfig, trace, resultTraces, false,
                                        cachedStackSlots);
                        IntervalData intervalData = getAndDelete(intervalMap, trace);
                        allocator.allocate(target, lirGenRes, codeEmittingOrder, linearScanOrder, spillMoveFactory, registerAllocationConfig, intervalData);
                    }
                    Debug.dump(TRACE_DUMP_LEVEL, trace, "After  Trace%s: %s", trace.getId(), trace);
                }
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        if (Debug.isDumpEnabled(Debug.INFO_LOG_LEVEL)) {
            unnumberInstructions(lir);
            Debug.dump(Debug.INFO_LOG_LEVEL, lir, "After trace allocation");
        }

        TRACE_GLOBAL_MOVE_RESOLUTION_PHASE.apply(target, lirGenRes, codeEmittingOrder, linearScanOrder, traceContext);
        deconstructSSIForm(lir);
    }

    private static <B extends AbstractBlockBase<B>> IntervalData getAndDelete(TraceIntervalMap intervalMap, Trace<B> trace) {
        if (intervalMap == null) {
            return null;
        }
        IntervalData intervalData = intervalMap.get(trace);
        // remove entry
        intervalMap.put(trace, null);
        return intervalData;
    }

    private static TraceIntervalMap getIntervalMap(AllocationContext context) {
        if (!AllocationStage.Options.TraceRACombinedSSIConstruction.getValue()) {
            return null;
        }
        return context.contextRemove(TraceIntervalMap.class);
    }

    @SuppressWarnings("unchecked")
    private static <B extends AbstractBlockBase<B>> TraceBuilderResult<B> getTraces(AllocationContext context) {
        return context.contextLookup(TraceBuilderResult.class);
    }

    /**
     * Remove Phi/Sigma In/Out.
     *
     * Note: Incoming Values are needed for the RegisterVerifier, otherwise SIGMAs/PHIs where the
     * Out and In value matches (ie. there is no resolution move) are falsely detected as errors.
     */
    @SuppressWarnings("try")
    private static void deconstructSSIForm(LIR lir) {
        for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
            try (Indent i = Debug.logAndIndent("Fixup Block %s", block)) {
                if (block.getPredecessorCount() != 0) {
                    SSIUtil.removeIncoming(lir, block);
                } else {
                    assert lir.getControlFlowGraph().getStartBlock().equals(block);
                }
                SSIUtil.removeOutgoing(lir, block);
            }
        }
    }

    private static void unnumberInstructions(LIR lir) {
        for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
            for (LIRInstruction op : lir.getLIRforBlock(block)) {
                op.setId(-1);
            }
        }
    }
}
