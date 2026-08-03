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
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.support.steps.input.Rejection;

/**
 * <p>Every non-plugin type or method that {@code WithEphemeralCredentials.groovy}
 * would otherwise need to call directly - {@link CredentialsProvider}, {@link
 * Run}, {@link ParameterDefinition}, {@link FlowInterruptedException}, {@link
 * Logger} - is routed through a plugin-owned method here instead. That
 * .groovy file is CPS-transformed and runs under the pipeline sandbox (see its
 * own class javadoc for why it has to be Groovy at all), so every method it
 * calls directly needs to be sandbox-approved (see {@link Whitelisted} below),
 * including ones on completely unrelated third-party classes it merely
 * happens to touch in passing. Calls made from <em>inside</em> an ordinary
 * compiled Java method body - like every method below - are never
 * sandbox-intercepted at all, regardless of what they touch internally, the
 * same way {@link EphemeralCredentialsProvider#put} or {@link
 * EphemeralCredentialsAccessor}'s methods already aren't. Concentrating all
 * of it here means only this plugin's own classes ever need sandbox approval.
 *
 * <h2>No {@code Run}/run ID parameter on any {@code @Whitelisted} method</h2>
 * <p>{@link Whitelisted} (like the {@code whitelist.txt} entries it replaces
 * - see below) approves a method signature globally, for every sandboxed
 * script on the whole Jenkins instance, not just for calls arriving via
 * {@code WithEphemeralCredentials.groovy}. An earlier revision of {@link
 * #isResolvable} and {@link #put} accepted the run's ID as a parameter,
 * supplied by the (sandboxed) caller - which meant <em>any</em> sandboxed
 * Pipeline anywhere could have called {@code
 * WithEphemeralCredentialsSupport.isResolvable("some-other-job#5",
 * "SOME_ID")} or the {@code put} equivalent directly, using nothing but a
 * guessed or known {@code externalizableId}, completely bypassing {@code
 * withEphemeralCredentials} and reading or poisoning a <em>different</em>
 * run's ephemeral credential cache. Neither method accepts a run identifier
 * from the caller anymore - both resolve "the run actually executing this
 * exact call" themselves, via {@link #requireCurrentRun()} (the same {@link
 * CpsRuns#current()}-based mechanism {@link
 * EphemeralCredentialsAccessor#forCurrentRun} already used), so a caller can
 * never direct either method at any run other than its own.</p>
 *
 * <h2>No free-text logging method either</h2>
 * <p>An earlier revision also exposed a generic {@code logFine(String
 * message)} passthrough to the sandboxed script, so {@code call()} could log
 * its own narrative messages. That let any sandboxed script write an
 * arbitrary, attacker-chosen message into Jenkins' own system log under this
 * plugin's logger name - a log-forging concern independent of the run-ID one
 * above. Logging now happens only inside methods whose message content is
 * entirely fixed by this class itself, with only well-typed data the method
 * already legitimately handles (a credential ID, the run it just resolved
 * itself) substituted in - never an arbitrary caller-supplied string.</p>
 */
final class WithEphemeralCredentialsSupport {

    private static final Logger LOGGER = Logger.getLogger(WithEphemeralCredentialsSupport.class.getName());

    private WithEphemeralCredentialsSupport() {}

    /**
     * Whether {@code credentialsId} already resolves to something, via the
     * normal global lookup (every registered store, including this plugin's
     * own), for whichever run is actually executing this call.
     */
    @Whitelisted
    static boolean isResolvable(@NonNull String credentialsId) {
        Run<?, ?> run = requireCurrentRun();
        boolean found = CredentialsProvider.findCredentialById(credentialsId, StandardCredentials.class, run) != null;
        LOGGER.fine(() -> "isResolvable('" + credentialsId + "') for run " + run.getExternalizableId() + " = " + found);
        return found;
    }

    /**
     * Caches an already-materialized credential for whichever run is
     * actually executing this call. See {@code
     * WithEphemeralCredentials.privatePut} for why resolving the {@link Run}
     * only happens here, never any earlier.
     */
    @Whitelisted
    static void put(@NonNull String credentialsId, @NonNull Credentials credentials) {
        Run<?, ?> run = requireCurrentRun();
        EphemeralCredentialsProvider.get().put(run, credentialsId, credentials);
        LOGGER.fine(() -> "put('" + credentialsId + "') cached for run " + run.getExternalizableId());
    }

    /**
     * Logs that {@code credentialsId} was left uncached because the {@code
     * input} prompt for it was declined - the one narrative message from the
     * old {@code call()} that doesn't naturally fall out of {@link
     * #isResolvable} or {@link #put} themselves, since declining means
     * neither is called again for this attempt.
     */
    @Whitelisted
    static void logInputDeclined(@NonNull String credentialsId) {
        Run<?, ?> run = requireCurrentRun();
        LOGGER.fine(() ->
                "input for '" + credentialsId + "' declined for run " + run.getExternalizableId() + " - not cached");
    }

    /**
     * Resolves "the run actually executing this exact call", via {@link
     * CpsRuns#current()} - never from a caller-supplied identifier, so a
     * sandboxed script can never point any method here at a run other than
     * its own. See the class javadoc.
     */
    @NonNull
    private static Run<?, ?> requireCurrentRun() {
        Run<?, ?> run = CpsRuns.current();
        if (run == null) {
            throw new IllegalStateException(
                    "withEphemeralCredentials can only be used from within a running " + "Pipeline build");
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
    @Whitelisted
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
    @Whitelisted
    static boolean isDeclinedInput(@NonNull FlowInterruptedException e) {
        for (CauseOfInterruption cause : e.getCauses()) {
            if (cause instanceof Rejection) {
                return true;
            }
        }
        return false;
    }
}
