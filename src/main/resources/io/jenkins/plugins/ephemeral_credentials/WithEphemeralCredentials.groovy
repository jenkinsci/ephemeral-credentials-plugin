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
package io.jenkins.plugins.ephemeral_credentials

import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardCredentials
import hudson.model.Run
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.workflow.support.steps.input.Rejection

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
 *
 * <p>Use of the {@code lock} step allows to limit the {@code input} of
 * a previously missing credential to one of possibly many {@code parallel}
 * or agent-bound stages that would want it: the first one to get the
 * lock would ask for it, and others would find it  already cached when
 * their turn comes. The user may cancel the {@code input} step, causing
 * the credential to remain unknown but not interrupting the pipeline
 * immediately in any other way.</p>
 *
 * <p>It is accompanied in plugin sources with a {@code whitelist.txt} file
 * to permit use of methods and classes that it refers to even in sandboxed
 * pipelines, see {@link EphemeralCredentialsWhitelist} class for technical
 * details.</p>
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
                script.lock("ephemeral-ephemeral_credentials-${runId}-${spec.id}") {
                    // Re-check, maybe another parallel branch has already asked
                    // for the credential (in its locked context, before this
                    // branch of the code flow got here):
                    if (CredentialsProvider.findCredentialById(spec.id, StandardCredentials.class, Run.fromExternalizableId(runId)) == null) {
                        List params = spec.inputParameters()
                        String message = spec.description ?: "Provide credential '${spec.id}'"
                        try {
                            script.echo("Waiting for input of credential '${spec.id}'" + (spec.description ? ": " + spec.description : ""))
                            def raw = script.input(message: message, parameters: params)
                            Map values = params.size() == 1 ? [(params[0].name): raw] : raw
                            EphemeralCredentialsProvider.get().put(Run.fromExternalizableId(runId), spec.id, spec.materialize(values))
                        } catch (FlowInterruptedException e) {
                            // Only swallow a genuine "user clicked Abort on
                            // this input" - identifiable by a Rejection
                            // cause, attached solely by InputStepExecution's
                            // own abort handling. Anything else carrying
                            // FlowInterruptedException (the whole build
                            // being stopped, a timeout, ...) must propagate
                            // so the build actually stops as intended,
                            // rather than being silently swallowed here.
                            if (!e.causes.any { it instanceof Rejection }) {
                                throw e
                            }
                            script.echo("Credential '${spec.id}' was not provided (input declined) - continuing without it.")
                        }
                    }
                }
            }
        }

        return body()
    }
}
