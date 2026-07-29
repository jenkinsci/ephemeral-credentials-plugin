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
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.cps.CpsThread;
import org.springframework.security.core.Authentication;

/**
 * <p>A {@link CredentialsProvider} that only ever answers with ephemeral_credentials that
 * some currently executing Pipeline build itself put into it. Everything is
 * held in a plain in-memory map, keyed by {@link Run#getExternalizableId()};
 * nothing here is {@code Saveable} and nothing is ever written to disk.</p>
 *
 * <p>This class is loaded once, as an ordinary {@code @Extension}, at Jenkins
 * startup - unlike a shared-library {@code src/} class, which is recompiled
 * per build and therefore cannot hold state shared across builds. That is
 * the whole reason this exists as a real plugin instead of living in a JSL.</p>
 *
 * <p>{@link #getCredentialsInItemGroup} is invoked by Jenkins' generic ephemeral_credentials
 * lookup from many unrelated contexts (job-config dropdowns, other plugins
 * enumerating what's available, freestyle builds, etc.), not just from a
 * deliberate request for a specific ID. It therefore stays purely passive -
 * it never prompts for anything, it only serves what has already been
 * {@link #put} into it. Deciding when to interactively resolve a missing
 * credential is the caller's job (see {@code WithEphemeralCredentials.groovy}
 * step).</p>
 *
 * <h2>Run correlation, and a discovered limitation</h2>
 * <p>Because a single Run's Pipeline script can be executing on several
 * different {@code node}/{@code agent} blocks at once (parallel branches)
 * or move between agents across sequential stages, there is no stable
 * {@code hudson.model.Executor} to correlate against. What is stable for
 * the whole life of the build is its {@code FlowExecutionOwner}, reachable
 * from whichever {@link CpsThread} happens to be running the code that
 * triggered this lookup - see {@link CpsRuns#current()}.</p>
 *
 * <p>That resolves correctly when the caller is itself CPS-interpreted code
 * (our own {@code WithEphemeralCredentials.groovy}, a shared-library script,
 * the Jenkinsfile itself). It does <b>not</b> resolve when the caller is a
 * step's own internal Java implementation running off the CPS interpreter
 * thread entirely - confirmed empirically against a real embedded Jenkins:
 * {@code credentials-binding}'s {@code withCredentials} performs its own
 * {@code findCredentialById} call from such a thread, where
 * {@code CpsThread.current()} is {@code null}.</p>
 *
 * <h2>The unidentifiable-run fallback, and why it's scoped to {@code itemGroup}</h2>
 * <p>When the run can't be identified, this falls back to considering other
 * runs' caches too and lets the caller's own by-ID filtering (e.g. {@code
 * CredentialsProvider.findCredentialById}) pick the right entry - but
 * <b>only among runs whose job lives within the {@code itemGroup} this
 * method was actually asked about</b>, not literally every cached run
 * project-wide. This matters: tracing {@code credentials-binding}'s actual
 * call path (its {@code Run}-based {@code findCredentialById} overload,
 * through {@code findCredentialByIdInItem(id, type, run.getParent(), ...)})
 * confirms that {@code itemGroup} here is the calling job's own containing
 * folder (or Jenkins root) - Jenkins' extension API simply never passes the
 * specific calling {@code Run} this deep, so there is no way to identify
 * the exact run from this method's parameters alone. Without the {@code
 * itemGroup} scoping, an unfiltered "every cached run" fallback would let a
 * build in one folder receive another, unrelated build's ephemeral value
 * from a completely different folder/team/permission scope, purely because
 * both happened to cache something under the same literal credential ID at
 * the same time - a genuine cross-tenant credential leak, not a theoretical
 * one, since Jenkins folder-scoped credentials exist specifically to
 * enforce that boundary. Scoping the fallback to {@code itemGroup}
 * containment restores that boundary. A narrower residual risk remains:
 * two concurrent runs of the same or sibling jobs *within the same folder
 * scope*, caching different values under the same literal ID, can still
 * collide - weigh that against how likely concurrent builds sharing an ID
 * are for whatever pipeline uses this.</p>
 */
@Extension
public class EphemeralCredentialsProvider extends CredentialsProvider {

    private static final Logger LOGGER = Logger.getLogger(EphemeralCredentialsProvider.class.getName());

    private final Map<String, Map<String, Credentials>> byRun = new ConcurrentHashMap<>();

    public static EphemeralCredentialsProvider get() {
        return ExtensionList.lookupSingleton(EphemeralCredentialsProvider.class);
    }

    @NonNull
    @Override
    public <C extends Credentials> List<C> getCredentialsInItemGroup(
            @NonNull Class<C> type,
            @NonNull ItemGroup itemGroup,
            @Nullable Authentication authentication,
            @NonNull List<DomainRequirement> domainRequirements) {
        Run<?, ?> run = CpsRuns.current();
        Collection<Map<String, Credentials>> candidates;
        if (run != null) {
            Map<String, Credentials> forRun = byRun.get(run.getExternalizableId());
            candidates = (forRun == null ? Collections.emptyList() : Collections.singletonList(forRun));
            LOGGER.fine(() -> "getCredentialsInItemGroup: run identified as " + run.getExternalizableId() + ", "
                    + (forRun == null ? "no ephemeral cache for it" : forRun.size() + " entries cached"));
        } else {
            // See the class javadoc: can't identify the run, so consider
            // other runs' caches too, but only those whose job lives within
            // the itemGroup we were actually asked about (e.g. a job Folder) -
            // not literally every cached run server-wide - and let by-ID filtering
            // downstream (e.g. CredentialsProvider.findCredentialById) pick
            // the right entry among those.
            List<Map<String, Credentials>> scoped = new ArrayList<>();
            for (Map.Entry<String, Map<String, Credentials>> entry : byRun.entrySet()) {
                Run<?, ?> candidateRun = Run.fromExternalizableId(entry.getKey());
                if (candidateRun != null && isWithin(itemGroup, candidateRun)) {
                    scoped.add(entry.getValue());
                }
            }
            candidates = scoped;
            LOGGER.fine(() -> "getCredentialsInItemGroup: run not identified, falling back to " + scoped.size()
                    + " run(s) cached within itemGroup " + itemGroup.getFullName());
        }

        List<C> result = new ArrayList<>();
        for (Map<String, Credentials> forRun : candidates) {
            for (Credentials candidate : forRun.values()) {
                if (type.isInstance(candidate)) {
                    result.add(type.cast(candidate));
                }
            }
        }
        LOGGER.fine(() -> "getCredentialsInItemGroup: returning " + result.size() + " candidate(s) of type "
                + type.getSimpleName());
        return result;
    }

    /**
     * Whether {@code run}'s own job lives inside {@code itemGroup} itself,
     * or inside any folder nested within it - the same direction Jenkins'
     * own folder-scoped credential visibility works (a folder's items can
     * see credentials scoped at that folder or an ancestor, never the
     * reverse).
     */
    private static boolean isWithin(@NonNull ItemGroup<?> itemGroup, @NonNull Run<?, ?> run) {
        ItemGroup<?> container = run.getParent().getParent();
        while (container != null) {
            if (container == itemGroup) {
                return true;
            }
            container = (container instanceof Item) ? ((Item) container).getParent() : null;
        }
        return false;
    }

    /**
     * Caches {@code ephemeral_credentials} under {@code credentialsId}, visible only to
     * lookups made from within {@code run}'s own Pipeline execution.
     */
    public void put(@NonNull Run<?, ?> run, @NonNull String credentialsId, @NonNull Credentials credentials) {
        byRun.computeIfAbsent(run.getExternalizableId(), key -> new ConcurrentHashMap<>())
                .put(credentialsId, credentials);
        LOGGER.fine(() ->
                "put: cached '" + credentialsId + "' for " + run.getExternalizableId() + " (never the value itself)");
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
