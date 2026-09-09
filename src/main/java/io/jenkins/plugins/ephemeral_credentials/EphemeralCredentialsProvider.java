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
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.ItemGroup;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.cps.CpsThread;
import org.springframework.security.core.Authentication;

/**
 * <p>A {@link CredentialsProvider} that only ever answers with ephemeral
 * credentials that some currently executing Pipeline build itself put into
 * it. Everything is held in a plain in-memory map, keyed by {@link
 * Run#getExternalizableId()}; nothing here is {@code Saveable} and nothing is
 * ever written to disk.</p>
 *
 * <p>This class is loaded once, as an ordinary {@code @Extension}, at Jenkins
 * startup - unlike a shared-library {@code src/} class, which is recompiled
 * per build and therefore cannot hold state shared across builds. That is
 * the whole reason this exists as a real plugin instead of living in a JSL.</p>
 *
 * <p>{@link #getCredentialsInItemGroup} is invoked by Jenkins' generic credentials
 * lookup from many unrelated contexts (job-config dropdowns, other plugins
 * enumerating what's available, freestyle builds, etc.), not just from a
 * deliberate request for a specific ID. It therefore stays purely passive -
 * it never prompts for anything, it only serves what has already been
 * {@link #put} into it. Deciding when to interactively resolve a missing
 * credential is the caller's job (see {@code WithEphemeralCredentials.groovy}
 * step).</p>
 *
 * <h2>Identifying which build is asking</h2>
 * <p>A single build's Pipeline script can be executing on several different
 * {@code node}/{@code agent} blocks at once (parallel branches) or move
 * between agents across sequential stages, so there is no stable {@code
 * hudson.model.Executor} to correlate against. What is stable for the whole
 * life of the build is its {@code FlowExecutionOwner}, reachable from
 * whichever {@link CpsThread} happens to be running the code that triggered
 * this lookup - see {@link CpsRuns#current()}.</p>
 *
 * <p>That resolves correctly when the caller is itself CPS-interpreted code
 * (our own {@code WithEphemeralCredentials.groovy}, a shared-library script,
 * the Jenkinsfile itself). It does <b>not</b> resolve when the caller is a
 * step's own internal Java implementation running off the CPS interpreter
 * thread entirely - {@code credentials-binding}'s {@code withCredentials},
 * for example, performs its own {@code findCredentialById} call from such a
 * thread, where {@link CpsThread#current()} is {@code null}.</p>
 *
 * <p>Unfortunately, this resolution by CPS threads rules out the use of
 * this plugin for legacy (Freestyle) builds where we currently have no
 * way of matching the currently running code path to a {@code Run} of
 * a build. If a solution to that problem is found, code contributions
 * are welcome.</p>
 *
 * <h2>Run identification: known, or nothing - never guessed</h2>
 * <p>Ephemeral credentials only ever mean something in the context of one
 * specific {@link Run}; there is no sensible answer to "which run's cache
 * applies here" other than the actual run asking the question. Every real
 * consumer of a credential ID -- {@code CredentialsProvider.findCredentialById
 * (id, type, Run, ...)} and everything built on it ({@code withCredentials},
 * {@code checkout}, {@code sshagent}, this plugin's own {@code
 * WithEphemeralCredentials}/{@code EphemeralCredentialsAccessor}) -- already
 * has the {@link Run} in hand.</p>
 *
 * <p>A {@code credentials-plugin} API update proposed in
 * <a href="https://github.com/jenkinsci/credentials-plugin/pull/1071">pull
 * request #1071</a> allows passing that {@code Run} object all the way down
 * to a provider implementation's own overridable methods instead of discarding
 * it before it gets here, as the earlier releases did (see
 * {@link #getCredentialsInItemGroup(Class, ItemGroup, Authentication, List,
 * Run)} below). With that feature in place, this class simply uses the value
 * directly -- no fallback correlation is attempted through any other channel.</p>
 *
 * <p>NOTE: Until the {@code credentials-plugin} API update is merged, a
 * version from Jenkins Incrementals or a locally built fork can be pinned
 * in {@code pom.xml} file.</p>
 *
 * <p>When a caller genuinely has no {@link Run} to give at all and calls the
 * 4-argument overload below, e.g. a job-config credential dropdown - the
 * only correct answer is an empty list: "no ephemeral credential applies
 * here," never a guess.</p>
 */
@Extension
public class EphemeralCredentialsProvider extends CredentialsProvider {

    private static final Logger LOGGER = Logger.getLogger(EphemeralCredentialsProvider.class.getName());

    // transient: this is a Descriptor (via CredentialsProvider), so Jenkins'
    // XStream-based descriptor persistence would otherwise be able to walk
    // and serialize this map to disk if anything ever called save() on this
    // singleton - nothing does today (no config form here), but transient
    // makes that a guarantee rather than an accident of current code paths.
    private final transient Map<String, Map<String, Credentials>> byRun = new ConcurrentHashMap<>();

    public static EphemeralCredentialsProvider get() {
        return ExtensionList.lookupSingleton(EphemeralCredentialsProvider.class);
    }

    /**
     * Called only when the caller has no {@link Run} to give at all - see
     * the 5-argument overload below, which is what every real Run-based
     * lookup actually goes through. Ephemeral credentials are meaningless
     * without knowing which run they belong to, so this always answers with
     * an empty list rather than guessing at one - see the class javadoc for
     * why an earlier revision's guesswork here was removed.
     */
    @NonNull
    @Override
    public <C extends Credentials> List<C> getCredentialsInItemGroup(
            @NonNull Class<C> type,
            @NonNull ItemGroup itemGroup,
            @Nullable Authentication authentication,
            @NonNull List<DomainRequirement> domainRequirements) {
        LOGGER.fine(() -> "getCredentialsInItemGroup: called without a Run (itemGroup=" + itemGroup.getFullName()
                + ") - ephemeral credentials are Run-scoped and cannot be resolved without one, returning an "
                + "empty list rather than guessing");
        return Collections.emptyList();
    }

    /**
     * The run-aware overload - see the class javadoc ("Run identification:
     * known, or nothing"). When {@code run} is supplied, it is used
     * directly: exactly this run's own cache, nothing else, no correlation
     * guesswork at all. When it isn't (a caller with no run to give, e.g. a
     * job-config credential dropdown), this falls through to the 4-argument
     * override above, which answers with an empty list rather than
     * guessing.
     */
    @NonNull
    @Override
    public <C extends Credentials> List<C> getCredentialsInItemGroup(
            @NonNull Class<C> type,
            @NonNull ItemGroup itemGroup,
            @Nullable Authentication authentication,
            @NonNull List<DomainRequirement> domainRequirements,
            @CheckForNull Run<?, ?> run) {
        if (run == null) {
            return getCredentialsInItemGroup(type, itemGroup, authentication, domainRequirements);
        }
        Map<String, Credentials> forRun = byRun.get(run.getExternalizableId());
        List<C> result = new ArrayList<>();
        if (forRun != null) {
            for (Credentials candidate : forRun.values()) {
                if (type.isInstance(candidate)) {
                    result.add(type.cast(candidate));
                }
            }
        }
        String runId = run.getExternalizableId();
        LOGGER.fine(() -> "getCredentialsInItemGroup(run): run=" + runId + " identified directly, returning "
                + result.size() + " candidate(s) of type " + type.getSimpleName());
        return result;
    }

    /**
     * Caches {@code ephemeral_credentials} under {@code credentialsId}, visible only to
     * lookups made from within {@code run}'s own Pipeline execution.
     */
    public void put(@NonNull Run<?, ?> run, @NonNull String credentialsId, @NonNull Credentials credentials) {
        byRun.computeIfAbsent(run.getExternalizableId(), key -> new ConcurrentHashMap<>())
                .put(credentialsId, credentials);
        // Never log the value itself, just the identifier:
        LOGGER.fine(() -> "put: cached '" + credentialsId + "' for " + run.getExternalizableId());
    }

    @CheckForNull
    public Credentials find(@NonNull Run<?, ?> run, @NonNull String credentialsId) {
        Map<String, Credentials> forRun = byRun.get(run.getExternalizableId());
        Credentials found = (forRun == null ? null : forRun.get(credentialsId));
        LOGGER.fine(() -> "find: '" + credentialsId + "' for " + run.getExternalizableId() + " - "
                + (found == null ? "not found" : "found"));
        return found;
    }

    public boolean has(@NonNull Run<?, ?> run, @NonNull String credentialsId) {
        return find(run, credentialsId) != null;
    }

    /**
     * Drops every credential cached for {@code run}. Called by
     * {@link EphemeralCredentialsRunListener} once the build is finalized or
     * deleted, regardless of how it ended - this is the authoritative
     * cleanup path, not any {@code finally} block in the pipeline script,
     * since a hard-killed build can skip the latter entirely.
     */
    public void forget(@NonNull Run<?, ?> run) {
        Map<String, Credentials> removed = byRun.remove(run.getExternalizableId());
        LOGGER.fine(() -> "forget: dropped " + (removed == null ? 0 : removed.size()) + " entries for "
                + run.getExternalizableId());
    }

    /**
     * Drops just {@code credentialsId} from {@code run}'s cache, leaving
     * any other entries for that run untouched - unlike {@link #forget(Run)},
     * which is the whole-run cleanup path called only by {@link
     * EphemeralCredentialsRunListener}. This overload is what backs the
     * pipeline-facing {@code ephemeralCredentialsForget}/{@code
     * EphemeralCredentialsAccessor} single-entry removal.
     *
     * @return whether an entry was actually present and removed.
     */
    public boolean forget(@NonNull Run<?, ?> run, @NonNull String credentialsId) {
        Map<String, Credentials> forRun = byRun.get(run.getExternalizableId());
        boolean removed = forRun != null && forRun.remove(credentialsId) != null;
        LOGGER.fine(() -> "forget: '" + credentialsId + "' for " + run.getExternalizableId() + " - "
                + (removed ? "removed" : "was not present"));
        return removed;
    }
}
