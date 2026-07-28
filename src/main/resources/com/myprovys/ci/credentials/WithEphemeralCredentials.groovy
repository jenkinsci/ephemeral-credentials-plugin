package com.myprovys.ci.credentials

import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardCredentials
import hudson.model.Run
import org.jenkinsci.plugins.workflow.cps.CpsScript

/**
 * <p>Backs the {@code withEphemeralCredentials} global step. Shipped as Groovy
 * source (not precompiled) so that, when loaded via the calling build's own
 * CpsScript classloader, it goes through the same CPS transformation as a
 * shared-library vars/src script - the same technique docker-workflow-plugin
 * uses for its {@code Docker.groovy}, and the reason calling script.lock(...) /
 * script.input(...) here is safe: this is genuine CPS-interpreted code, not
 * a plain Java call reaching into a stored script reference.</p>
 *
 * <p>Holds only the Run's plain {@code externalizableId} String, never a live
 * {@code Run} object - CPS serializes this instance (it sits in the script's
 * binding) whenever the pipeline pauses inside {@link #call}, and
 * {@code WorkflowRun} is not Java-serializable.</p>
 *
 * <p>The actual {@code Run} instance is re-resolved right before each use
 * via {@code Run.fromExternalizableId(...)} and never held across a
 * {@code lock}/{@code input} pause point - same reasoning applies to
 * the {@code EphemeralCredentialsProvider} singleton, fetched fresh
 * rather than cached in a field.</p>
 *
 * <p>This step requires that the CPS script context provides the
 * {@code input} and {@code lock} steps provided by "pipeline-input-step"
 * and "lockable-resources" plugins respectively (or some mocks that
 * follow their API).</p>
 */
class WithEphemeralCredentials implements Serializable {

    private static final long serialVersionUID = 1L

    private final CpsScript script
    private final String runId

    WithEphemeralCredentials(CpsScript script, String runId) {
        this.script = script
        this.runId = runId
    }

    def call(List<EphemeralCredentialSpec> specs = [], Closure body) {
        for (EphemeralCredentialSpec spec in specs) {
            // Ask via the normal, global lookup first - this consults every
            // registered store (System/Folder/Job-scoped, and our own
            // ephemeral one too), so an ID that's already resolvable
            // anywhere else is left alone; only a genuinely missing one
            // reaches the interactive path below.
            if (CredentialsProvider.findCredentialById(spec.id, StandardCredentials.class, Run.fromExternalizableId(runId)) == null) {
                script.lock("ephemeral-credentials-${runId}-${spec.id}") {
                    if (CredentialsProvider.findCredentialById(spec.id, StandardCredentials.class, Run.fromExternalizableId(runId)) == null) {
                        List params = spec.inputParameters()
                        String message = spec.description ?: "Provide credential '${spec.id}'"
                        def raw = script.input(message: message, parameters: params)
                        Map values = params.size() == 1 ? [(params[0].name): raw] : raw
                        EphemeralCredentialsProvider.get().put(Run.fromExternalizableId(runId), spec.id, spec.materialize(values))
                    }
                }
            }
        }

        return body()
    }
}
