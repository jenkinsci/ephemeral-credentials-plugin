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
package io.jenkins.plugins.ephemeral_credentials;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.ParameterDefinition;
import hudson.model.Run;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.support.steps.input.Rejection;

/**
 * <p>Every non-plugin type or method that {@code WithEphemeralCredentials.groovy}
 * would otherwise need to call directly - {@link CredentialsProvider}, {@link
 * Run}, {@link ParameterDefinition}, {@link FlowInterruptedException}, {@link
 * Logger} - is routed through a plugin-owned method here instead. That
 * .groovy file is CPS-transformed and runs under the pipeline sandbox (see its
 * own class javadoc for why it has to be Groovy at all), so every method it
 * calls directly needs its own {@code whitelist.txt} entry, including ones on
 * completely unrelated third-party classes it merely happens to touch in
 * passing. Calls made from <em>inside</em> an ordinary compiled Java method
 * body - like every method below - are never sandbox-intercepted at all,
 * regardless of what they touch internally, the same way {@link
 * EphemeralCredentialsProvider#put} or {@link EphemeralCredentialsAccessor}'s
 * methods already aren't. Concentrating all of it here means {@code
 * whitelist.txt} only ever needs to list this plugin's own classes.
 */
final class WithEphemeralCredentialsSupport {

    private static final Logger LOGGER = Logger.getLogger(WithEphemeralCredentialsSupport.class.getName());

    private WithEphemeralCredentialsSupport() {}

    /**
     * Whether {@code credentialsId} already resolves to something, via the
     * normal global lookup (every registered store, including this plugin's
     * own), for the run identified by {@code runId}.
     */
    static boolean isResolvable(@NonNull String runId, @NonNull String credentialsId) {
        return CredentialsProvider.findCredentialById(credentialsId, StandardCredentials.class, requireRun(runId))
                != null;
    }

    /**
     * Caches an already-materialized credential for the run identified by
     * {@code runId}. See {@code WithEphemeralCredentials.privatePut} for why
     * resolving the {@link Run} only happens here, never any earlier.
     */
    static void put(@NonNull String runId, @NonNull String credentialsId, @NonNull Credentials credentials) {
        EphemeralCredentialsProvider.get().put(requireRun(runId), credentialsId, credentials);
    }

    /**
     * {@link Run#fromExternalizableId} is {@code @CheckForNull} in general,
     * but every {@code runId} reaching this class is this method's own
     * caller's currently-executing run - it should always still be
     * resolvable. Failing loudly here beats silently treating an
     * unresolvable run the same as "no credential found."
     */
    @NonNull
    private static Run<?, ?> requireRun(@NonNull String runId) {
        Run<?, ?> run = Run.fromExternalizableId(runId);
        if (run == null) {
            throw new IllegalStateException("Run " + runId + " could not be resolved");
        }
        return run;
    }

    /**
     * Turns the {@code input} step's raw answer into the {@code Map} {@link
     * EphemeralCredentialSpec#materialize} expects: {@code input} itself
     * returns the answer directly (not wrapped in a {@code Map}) whenever
     * there was exactly one parameter to ask for, so that one case is
     * re-wrapped under that parameter's own name; any other case already
     * came back as a {@code Map} from {@code input} itself.
     */
    @NonNull
    @SuppressWarnings("unchecked")
    static Map<String, Object> toValuesMap(@NonNull List<ParameterDefinition> params, Object inputAnswer) {
        if (params.size() == 1) {
            return Collections.singletonMap(params.get(0).getName(), inputAnswer);
        }
        return (Map<String, Object>) inputAnswer;
    }

    /**
     * Whether {@code e} represents a user explicitly declining an {@code
     * input} prompt (clicking Abort) - identifiable by a {@link Rejection}
     * cause, attached solely by {@code InputStepExecution}'s own abort
     * handling - as opposed to any other reason a build's flow might be
     * interrupted (the whole build being stopped, a timeout, ...), which
     * must propagate rather than being silently swallowed.
     */
    static boolean isDeclinedInput(@NonNull FlowInterruptedException e) {
        for (CauseOfInterruption cause : e.getCauses()) {
            if (cause instanceof Rejection) {
                return true;
            }
        }
        return false;
    }

    /** Routes to Jenkins' own system log (java.util.logging), never the build console - never pass a secret value. */
    static void logFine(@NonNull String message) {
        LOGGER.fine(message);
    }
}
