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

import com.cloudbees.groovy.cps.NonCPS
import com.cloudbees.plugins.credentials.Credentials
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
 * <p>Backs the {@code withEphemeralCredentials} global step. Shipped as Groovy
 * source (not precompiled) so that, when loaded via the calling build's own
 * CpsScript classloader, it goes through the same CPS transformation as a
 * shared-library vars/src script - the same technique docker-workflow-plugin
 * uses for its {@code Docker.groovy}, and the reason calling script.lock(...) /
 * script.input(...) here is safe: this is genuine CPS-interpreted code, not
 * a plain Java call reaching into a stored script reference. It also means
 * every method called directly from here runs under the pipeline sandbox and
 * needs a {@code whitelist.txt} entry - see "Keeping whitelist.txt
 * plugin-only" below for why that list only ever names this plugin's own
 * classes.</p>
 *
 * <p>Holds only the Run's plain {@code externalizableId} String, never a live
 * {@code Run} object - CPS serializes this instance (it sits in the script's
 * binding) whenever the pipeline pauses inside {@link #call}, and
 * {@code WorkflowRun} is not Java-serializable.</p>
 *
 * <p>The actual {@code Run} instance is re-resolved right before each use -
 * inside {@link WithEphemeralCredentialsSupport}, see below - and never held
 * across a {@code lock}/{@code input} pause point. This is a real, confirmed
 * failure mode, not just a defensive style rule: an intermediate version of
 * {@link #call} evaluated {@code Run.fromExternalizableId(runId)} as one
 * argument of the same {@code EphemeralCredentialsProvider.get().put(...)}
 * call whose *other* argument was {@code script.input(...)} - Groovy
 * evaluates arguments left to right, so the freshly-resolved, non-serializable
 * {@code Run} became a pending argument sitting in the continuation exactly
 * while {@code input} was suspended waiting for a human. The next time CPS
 * checkpointed the paused program to {@code program.dat}, that failed with
 * {@code NotSerializableException: WorkflowRun}, breaking the pipeline
 * (confirmed against a real embedded Jenkins). The fix is what's here now:
 * {@code input}'s own answer is never assigned to a named variable and never
 * shares an argument list with anything that resolves a {@code Run} - it
 * flows straight from {@code script.input(...)} through {@link
 * WithEphemeralCredentialsSupport#toValuesMap} into {@link
 * EphemeralCredentialSpec#materialize}, whose result is passed directly as
 * an argument to {@link #privatePut}, and {@link #privatePut} is the
 * <em>only</em> thing that ever resolves a {@code Run} on this path - by
 * which point {@code input} has already returned and nothing is pending a
 * suspend.</p>
 *
 * <p>{@link #privatePut} is additionally annotated {@code @NonCPS}, so it
 * runs as ordinary, non-suspendable code with no CPS continuation of its own -
 * a stronger guarantee than merely "no step call happens in between." This is
 * safe to do here - unlike an earlier, broader attempt at wrapping the whole
 * materialize-and-cache logic in {@code @NonCPS}, which was reverted -
 * because {@link #privatePut} itself never calls {@link
 * EphemeralCredentialSpec#materialize}: that still happens in {@link #call}'s
 * own CPS-transformed code, where it belongs, since a custom {@link
 * EphemeralCredentialSpec} defined as a JSL {@code src/} class (see
 * "Extending with more types" in the README) is itself CPS-transformed
 * Groovy, and a {@code @NonCPS} method cannot call into CPS-transformed code
 * at all - confirmed empirically: the earlier attempt failed every such call
 * with {@code CpsCallableInvocation}'s "expected to call X but wound up
 * catching Y" mismatch error, breaking that entire extension mechanism.
 * {@link #privatePut} only ever touches plain, always-precompiled types
 * ({@link Credentials}, {@link WithEphemeralCredentialsSupport}), so it has
 * no such risk.</p>
 *
 * <h2>Keeping whitelist.txt plugin-only</h2>
 * <p>Every call this class makes onto a type it doesn't itself define -
 * {@code CredentialsProvider.findCredentialById}, {@code
 * Run.fromExternalizableId}, {@code ParameterDefinition.getName()}, {@code
 * FlowInterruptedException.getCauses()}, {@code Logger.getLogger}/{@code
 * .fine(...)} - is instead made by {@link WithEphemeralCredentialsSupport},
 * a plain compiled Java class. Calls made from inside an ordinary compiled
 * Java method body are never sandbox-intercepted, regardless of what they
 * touch internally (the same reason {@link EphemeralCredentialsProvider#put}
 * or {@link EphemeralCredentialsAccessor}'s own methods never needed entries
 * for what they do internally either) - only the single hop from this
 * sandboxed Groovy file into a plugin-owned Java method needs an entry, and
 * that entry names this plugin's own class. So {@code whitelist.txt} only
 * ever needs to list types this plugin itself delivers, never a third-party
 * one - see {@link EphemeralCredentialsWhitelist} for how that file is
 * loaded.</p>
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
            //
            // Logged at FINE, not echoed to the build console: this goes to
            // Jenkins' own system log (java.util.logging), same as
            // EphemeralCredentialsProvider's own logging - never the secret
            // value itself, only whether one was found and where.
            if (!WithEphemeralCredentialsSupport.isResolvable(runId, spec.id)) {
                WithEphemeralCredentialsSupport.logFine("call: '" + spec.id + "' not found in any store for " + runId + " - will collect interactively")
                script.lock("ephemeral_credentials-${runId}-${spec.id}") {
                    // Re-check, maybe another parallel branch has already asked
                    // for the credential (in its locked context, before this
                    // branch of the code flow got here):
                    if (!WithEphemeralCredentialsSupport.isResolvable(runId, spec.id)) {
                        List params = spec.inputParameters()
                        String message = spec.description ?: "Provide credential '${spec.id}'"
                        try {
                            script.echo("Waiting for input of credential '${spec.id}'" + (spec.description ? ": " + spec.description : ""))
                            // input's own answer is never assigned to a named
                            // groovy variable - see the class javadoc for why.
                            privatePut(spec.id, spec.materialize(WithEphemeralCredentialsSupport.toValuesMap(
                                    params, script.input(message: message, parameters: params))))
                            WithEphemeralCredentialsSupport.logFine("call: '" + spec.id + "' collected via input and cached for " + runId)
                        } catch (FlowInterruptedException e) {
                            // Only swallow a genuine "user clicked Abort on
                            // this input"; anything else carrying
                            // FlowInterruptedException (the whole build
                            // being stopped, a timeout, ...) must propagate
                            // so the build actually stops as intended,
                            // rather than being silently swallowed here.
                            if (!WithEphemeralCredentialsSupport.isDeclinedInput(e)) {
                                throw e
                            }
                            script.echo("Credential '${spec.id}' was not provided (input declined) - continuing without it.")
                            WithEphemeralCredentialsSupport.logFine("call: '" + spec.id + "' input declined for " + runId + " - not cached")
                        }
                    } else {
                        WithEphemeralCredentialsSupport.logFine("call: '" + spec.id + "' found already cached for " + runId
                                + " by a parallel branch while waiting for the lock")
                    }
                }
            } else {
                WithEphemeralCredentialsSupport.logFine("call: '" + spec.id + "' already resolvable via an existing store for " + runId
                        + " - no input needed")
            }
        }

        return body()
    }

    /**
     * Caches an already-materialized credential for this run - see the
     * class javadoc for why this is safe to mark {@code @NonCPS} here,
     * unlike the broader attempt in earlier iterations that was reverted.
     */
    @NonCPS
    private void privatePut(String credentialsId, Credentials credentials) {
        WithEphemeralCredentialsSupport.put(runId, credentialsId, credentials)
    }
}
