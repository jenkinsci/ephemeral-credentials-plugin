/*
 * The MIT License
 *
 * Copyright (c) 2026, Jim Klimov, PROVYS Technologies a.s.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.myprovys.ci.ephemeral_credentials;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Queue;
import hudson.model.Run;
import java.io.IOException;
import org.jenkinsci.plugins.workflow.cps.CpsThread;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;

/**
 * Resolves "which Run is the calling CPS thread executing for" - anchored to
 * the whole-build-lifetime {@code CpsFlowExecution}, not to any transient
 * per-{@code node}-block {@code Executor}, so it stays correct across
 * parallel branches and sequential stage/agent changes. Returns null when
 * called from outside a running Pipeline step (e.g. a job-config ephemeral_credentials
 * dropdown, a freestyle build, or another plugin's incidental enumeration).
 */
final class CpsRuns {

    private CpsRuns() {}

    @CheckForNull
    static Run<?, ?> current() {
        CpsThread thread = CpsThread.current();
        if (thread == null) {
            return null;
        }
        FlowExecutionOwner owner = thread.getExecution().getOwner();
        if (owner == null) {
            return null;
        }
        try {
            Queue.Executable executable = owner.getExecutable();
            return executable instanceof Run ? (Run<?, ?>) executable : null;
        } catch (IOException e) {
            return null;
        }
    }
}
