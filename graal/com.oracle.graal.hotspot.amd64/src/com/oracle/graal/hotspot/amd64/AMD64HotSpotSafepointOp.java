/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.asm.NumUtil.isInt;
import static com.oracle.graal.compiler.common.GraalOptions.ImmutableCode;
import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rip;

import com.oracle.graal.asm.amd64.AMD64Address;
import com.oracle.graal.asm.amd64.AMD64MacroAssembler;
import com.oracle.graal.compiler.common.LIRKind;
import com.oracle.graal.lir.LIRFrameState;
import com.oracle.graal.lir.LIRInstructionClass;
import com.oracle.graal.lir.Opcode;
import com.oracle.graal.lir.amd64.AMD64LIRInstruction;
import com.oracle.graal.lir.asm.CompilationResultBuilder;
import com.oracle.graal.nodes.spi.NodeLIRBuilderTool;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.site.InfopointReason;
import jdk.vm.ci.hotspot.HotSpotVMConfig;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;

/**
 * Emits a safepoint poll.
 */
@Opcode("SAFEPOINT")
public final class AMD64HotSpotSafepointOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64HotSpotSafepointOp> TYPE = LIRInstructionClass.create(AMD64HotSpotSafepointOp.class);

    @State protected LIRFrameState state;
    @Temp({OperandFlag.REG, OperandFlag.ILLEGAL}) private AllocatableValue temp;

    private final HotSpotVMConfig config;

    public AMD64HotSpotSafepointOp(LIRFrameState state, HotSpotVMConfig config, NodeLIRBuilderTool tool) {
        super(TYPE);
        this.state = state;
        this.config = config;
        if (isPollingPageFar(config) || ImmutableCode.getValue()) {
            temp = tool.getLIRGeneratorTool().newVariable(LIRKind.value(tool.getLIRGeneratorTool().target().arch.getWordKind()));
        } else {
            // Don't waste a register if it's unneeded
            temp = Value.ILLEGAL;
        }
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler asm) {
        emitCode(crb, asm, config, false, state, temp instanceof RegisterValue ? ((RegisterValue) temp).getRegister() : null);
    }

    /**
     * Tests if the polling page address can be reached from the code cache with 32-bit
     * displacements.
     */
    private static boolean isPollingPageFar(HotSpotVMConfig config) {
        final long pollingPageAddress = config.safepointPollingAddress;
        return config.forceUnreachable || !isInt(pollingPageAddress - config.codeCacheLowBound) || !isInt(pollingPageAddress - config.codeCacheHighBound);
    }

    public static void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler asm, HotSpotVMConfig config, boolean atReturn, LIRFrameState state, Register scratch) {
        assert !atReturn || state == null : "state is unneeded at return";
        if (ImmutableCode.getValue()) {
            JavaKind hostWordKind = JavaKind.Long;
            int alignment = hostWordKind.getBitCount() / Byte.SIZE;
            JavaConstant pollingPageAddress = JavaConstant.forIntegerKind(hostWordKind, config.safepointPollingAddress);
            // This move will be patched to load the safepoint page from a data segment
            // co-located with the immutable code.
            asm.movq(scratch, (AMD64Address) crb.recordDataReferenceInCode(pollingPageAddress, alignment));
            final int pos = asm.position();
            crb.recordMark(atReturn ? config.MARKID_POLL_RETURN_FAR : config.MARKID_POLL_FAR);
            if (state != null) {
                crb.recordInfopoint(pos, state, InfopointReason.SAFEPOINT);
            }
            asm.testl(rax, new AMD64Address(scratch));
        } else if (isPollingPageFar(config)) {
            asm.movq(scratch, config.safepointPollingAddress);
            crb.recordMark(atReturn ? config.MARKID_POLL_RETURN_FAR : config.MARKID_POLL_FAR);
            final int pos = asm.position();
            if (state != null) {
                crb.recordInfopoint(pos, state, InfopointReason.SAFEPOINT);
            }
            asm.testl(rax, new AMD64Address(scratch));
        } else {
            crb.recordMark(atReturn ? config.MARKID_POLL_RETURN_NEAR : config.MARKID_POLL_NEAR);
            final int pos = asm.position();
            if (state != null) {
                crb.recordInfopoint(pos, state, InfopointReason.SAFEPOINT);
            }
            // The C++ code transforms the polling page offset into an RIP displacement
            // to the real address at that offset in the polling page.
            asm.testl(rax, new AMD64Address(rip, 0));
        }
    }
}
