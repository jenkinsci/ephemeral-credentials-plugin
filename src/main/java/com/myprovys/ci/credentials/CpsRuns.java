package com.myprovys.ci.credentials;

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
 * called from outside a running Pipeline step (e.g. a job-config credentials
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
