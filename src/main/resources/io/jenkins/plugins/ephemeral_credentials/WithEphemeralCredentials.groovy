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

import java.util.logging.Logger

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
 * rather than cached in a field. This is a real, confirmed failure mode,
 * not just a defensive style rule: an intermediate version of {@link #call}
 * evaluated {@code Run.fromExternalizableId(runId)} as one argument of the
 * same {@code EphemeralCredentialsProvider.get().put(...)} call whose
 * *other* argument was {@code script.input(...)} - Groovy evaluates
 * arguments left to right, so the freshly-resolved, non-serializable
 * {@code Run} became a pending argument sitting in the continuation
 * exactly while {@code input} was suspended waiting for a human. The next
 * time CPS checkpointed the paused program to {@code program.dat}, that
 * failed with {@code NotSerializableException: WorkflowRun}, breaking the
 * pipeline (confirmed against a real embedded Jenkins). The fix is what's
 * here now: capture {@code input}'s answer into a plain local first, and
 * only resolve {@code Run.fromExternalizableId(...)} afterwards, once
 * {@code input} has already returned and nothing is pending a suspend.</p>
 *
 * <p>{@code input}'s own answer is never held across a further pause point
 * either: it flows straight from {@code script.input(...)} into
 * {@link EphemeralCredentialSpec#materialize}, then into
 * {@code EphemeralCredentialsProvider.put(...)}, with no other step call
 * (hence no further CPS checkpoint) happening in between - CPS's own
 * program-state persistence to {@code program.dat} is tied to step
 * invocations, not to plain statement execution, so nothing forces a
 * mid-computation write here. (A {@code @NonCPS} helper method was tried
 * here for extra assurance and reverted: {@link EphemeralCredentialSpec}
 * subclasses aren't necessarily plain precompiled Java the way this
 * plugin's own five are - one defined in a JSL {@code src/} class (see
 * "Extending with more types" in the README) is itself CPS-transformed
 * Groovy, and a {@code @NonCPS} method can't call into CPS-transformed code
 * at all - confirmed empirically: it fails every such call with {@code
 * CpsCallableInvocation}'s "expected to call X but wound up catching Y"
 * mismatch error, breaking that entire extension mechanism.)</p>
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

    private static final Logger LOGGER = Logger.getLogger(WithEphemeralCredentials.class.getName())

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
            //
            // Logged at FINE, not echoed to the build console: this goes to
            // Jenkins' own system log (java.util.logging), same as
            // EphemeralCredentialsProvider's own logging - never the secret
            // value itself, only whether one was found and where.
            if (CredentialsProvider.findCredentialById(spec.id, StandardCredentials.class, Run.fromExternalizableId(runId)) == null) {
                LOGGER.fine("call: '" + spec.id + "' not found in any store for " + runId + " - will collect interactively")
                script.lock("ephemeral-ephemeral_credentials-${runId}-${spec.id}") {
                    // Re-check, maybe another parallel branch has already asked
                    // for the credential (in its locked context, before this
                    // branch of the code flow got here):
                    if (CredentialsProvider.findCredentialById(spec.id, StandardCredentials.class, Run.fromExternalizableId(runId)) == null) {
                        List params = spec.inputParameters()
                        String message = spec.description ?: "Provide credential '${spec.id}'"
                        try {
                            script.echo("Waiting for input of credential '${spec.id}'" + (spec.description ? ": " + spec.description : ""))
                            // Avoid storing the input step output in a named
                            // groovy variable, so it can not be serialized by CPS:
                            if (params.size() == 1) {
                                EphemeralCredentialsProvider.get().put(Run.fromExternalizableId(runId), spec.id, spec.materialize(
                                    [(params[0].name): script.input(message: message, parameters: params)]))
                            } else {
                                EphemeralCredentialsProvider.get().put(Run.fromExternalizableId(runId), spec.id, spec.materialize(
                                        (Map)(script.input(message: message, parameters: params))))
                            }
                            LOGGER.fine("call: '" + spec.id + "' collected via input and cached for " + runId)
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
                            LOGGER.fine("call: '" + spec.id + "' input declined for " + runId + " - not cached")
                        }
                    } else {
                        LOGGER.fine("call: '" + spec.id + "' found already cached for " + runId
                                + " by a parallel branch while waiting for the lock")
                    }
                }
            } else {
                LOGGER.fine("call: '" + spec.id + "' already resolvable via an existing store for " + runId
                        + " - no input needed")
            }
        }

        return body()
    }
}
