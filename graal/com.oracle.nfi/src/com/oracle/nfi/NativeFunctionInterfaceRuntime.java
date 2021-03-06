/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.nfi;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.ServiceLoader;

import com.oracle.nfi.api.NativeFunctionInterface;
import com.oracle.nfi.api.NativeFunctionInterfaceAccess;

/**
 * Class for obtaining the {@link NativeFunctionInterface} (if any) provided by the VM.
 */
public final class NativeFunctionInterfaceRuntime {
    private static final NativeFunctionInterface INSTANCE;

    /**
     * Gets the {@link NativeFunctionInterface} (if any) provided by the VM.
     *
     * @return null if the VM does not provide a {@link NativeFunctionInterface}
     */
    public static NativeFunctionInterface getNativeFunctionInterface() {
        return INSTANCE;
    }

    static {

        NativeFunctionInterface instance = null;

        NativeFunctionInterfaceAccess access = null;
        Class<?> servicesClass = null;
        boolean jdk8OrEarlier = System.getProperty("java.specification.version").compareTo("1.9") < 0;
        if (!jdk8OrEarlier) {
            Iterator<NativeFunctionInterfaceAccess> providers = ServiceLoader.load(NativeFunctionInterfaceAccess.class).iterator();
            if (providers.hasNext()) {
                access = providers.next();
                if (providers.hasNext()) {
                    throw new InternalError(String.format("Multiple %s providers found", NativeFunctionInterfaceAccess.class.getName()));
                }
            }
        } else {

            try {
                servicesClass = Class.forName("jdk.vm.ci.services.Services");
            } catch (ClassNotFoundException e) {
                try {
                    servicesClass = Class.forName("jdk.vm.ci.service.Services");
                } catch (ClassNotFoundException e2) {
                    try {
                        // Legacy support
                        servicesClass = Class.forName("com.oracle.jvmci.service.Services");
                    } catch (ClassNotFoundException e3) {
                        // JVMCI is unavailable
                    }
                }
            }
        }
        if (servicesClass != null) {
            try {
                Method m = servicesClass.getDeclaredMethod("loadSingle", Class.class, boolean.class);
                access = (NativeFunctionInterfaceAccess) m.invoke(null, NativeFunctionInterfaceAccess.class, false);
            } catch (Throwable e) {
                // Fail fast for other errors
                throw (InternalError) new InternalError().initCause(e);
            }
        }
        // TODO: try standard ServiceLoader?
        if (access != null) {
            instance = access.getNativeFunctionInterface();
        }
        INSTANCE = instance;
    }
}
