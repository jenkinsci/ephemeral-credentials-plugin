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
import com.cloudbees.plugins.credentials.common.IdCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import java.io.Serializable;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;

/**
 * <p>A convenient, pipeline-facing handle onto {@link EphemeralCredentialsProvider}'s
 * store, permanently scoped to a single build - every operation is
 * constrained to whichever run this accessor was created for, the same way
 * {@code withEphemeralCredentials} itself only ever touches its own run's
 * cache. Exists so pipelines can pre-populate, inspect, or drop entries in
 * that store directly (e.g. computed at runtime, fetched from some other
 * secrets source mid-pipeline) without a plugin release or any custom
 * sandbox approval of their own.</p>
 *
 * <p>Holds only the run's plain {@code externalizableId} String, never a
 * live {@code Run} object, for the same reason {@code
 * WithEphemeralCredentials.groovy} does: this instance can end up sitting in
 * a pipeline script's own variables, which CPS's program-state serialization
 * walks whenever the build pauses on a step, and {@code WorkflowRun} isn't
 * Java-serializable.</p>
 *
 * <p>Exposes the same four operations two ways:</p>
 * <ul>
 *     <li>Plain named methods ({@link #find}/{@link #has}/{@link #put}/
 *     {@link #forget}) - what the separate {@code ephemeralCredentialsPut}/
 *     {@code Find}/{@code Has}/{@code Forget} global steps delegate to,
 *     constructing a fresh, one-shot instance per call.</li>
 *     <li>Groovy's operator sugar for {@code Map}-like access -
 *     {@link #getAt}/{@link #putAt}/{@link #containsKey}/{@link #remove} -
 *     via the {@code ephemeralCredentials} global variable, which returns
 *     one instance bound to the current run for the rest of the script
 *     (cached the same way {@code withEphemeralCredentials} itself is, see
 *     {@link EphemeralCredentialsAccessorGlobalVariable}), so
 *     {@code ephemeralCredentials['FOO'] = someCredentials} and
 *     {@code ephemeralCredentials['FOO']} work directly.</li>
 * </ul>
 *
 * <p>This does <b>not</b> implement {@link java.util.Map} itself - only the
 * handful of operations {@link EphemeralCredentialsProvider}'s own store
 * actually supports (single-key get/put/contains/remove). There is
 * deliberately no {@code entrySet()}/{@code values()}/{@code clear()}: this
 * store already only ever holds what the current run itself put there, so
 * bulk enumeration isn't a scoping concern the way it is for the fallback
 * discussed in {@link EphemeralCredentialsProvider}'s own javadoc, but
 * offering a full formal {@code Map} contract would invite pipelines to
 * write code that assumes semantics (iteration order, {@code equals()}/
 * {@code hashCode()} on the whole map, ...) this class was never meant to
 * promise.</p>
 */
public final class EphemeralCredentialsAccessor implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String runId;

    public EphemeralCredentialsAccessor(@NonNull String runId) {
        this.runId = runId;
    }

    /**
     * Resolves the current run via {@link CpsRuns#current()} and builds an
     * accessor for it, or throws a clear {@link IllegalStateException} if
     * this isn't being called from within a running Pipeline build -
     * shared by every global step/variable in this file so they all fail
     * the same way for the same reason.
     */
    @NonNull
    static EphemeralCredentialsAccessor forCurrentRun(@NonNull String stepName) {
        Run<?, ?> run = CpsRuns.current();
        if (run == null) {
            throw new IllegalStateException(stepName + " can only be used from within a running Pipeline build");
        }
        return new EphemeralCredentialsAccessor(run.getExternalizableId());
    }

    private Run<?, ?> run() {
        return Run.fromExternalizableId(runId);
    }

    @Whitelisted
    @CheckForNull
    public Credentials find(@NonNull String credentialsId) {
        return EphemeralCredentialsProvider.get().find(run(), credentialsId);
    }

    @Whitelisted
    public boolean has(@NonNull String credentialsId) {
        return EphemeralCredentialsProvider.get().has(run(), credentialsId);
    }

    /**
     * Caches an already-built {@link Credentials} object under {@code
     * credentialsId}. If {@code credentials} is {@link IdCredentials} (true
     * of every standard credential type, including everything this plugin's
     * own five factories produce), its own {@code getId()} must equal
     * {@code credentialsId} - otherwise standard lookups like {@code
     * withCredentials} match candidates by calling {@code getId()} on the
     * credential object itself, not by whatever key this store happens to
     * file it under, so a mismatch here would silently store something
     * that's cached but unfindable through any normal path. Confirmed the
     * hard way: an earlier version of this method skipped this check, and a
     * credential materialized as {@code ephemeralUsernamePassword(id:
     * 'FOO', ...)} then re-{@code put} under a different ID via this method
     * remained permanently invisible to {@code withCredentials} for that
     * other ID, with no error anywhere to explain why.
     */
    @Whitelisted
    public void put(@NonNull String credentialsId, @NonNull Credentials credentials) {
        if (credentials instanceof IdCredentials) {
            String ownId = ((IdCredentials) credentials).getId();
            if (!credentialsId.equals(ownId)) {
                throw new IllegalArgumentException("Credentials object's own ID ('" + ownId
                        + "') does not match the ID it's being cached under ('" + credentialsId
                        + "') - standard lookups (e.g. withCredentials) match by the credential's own ID, "
                        + "not by this store's key, so this would be stored but never findable");
            }
        }
        EphemeralCredentialsProvider.get().put(run(), credentialsId, credentials);
    }

    /**
     * Convenience overload matching what {@code withEphemeralCredentials}
     * itself does internally: materializes {@code spec} from {@code values}
     * (the same shape {@code input}'s answer would have been) and caches
     * the result under {@code spec.getId()} - lets a pipeline pre-populate
     * a credential from data it already has (fetched from some other
     * secrets source mid-pipeline, computed, ...) without ever pausing on
     * {@code input} at all.
     */
    @Whitelisted
    public void put(@NonNull EphemeralCredentialSpec spec, @NonNull Map<String, Object> values) {
        put(spec.getId(), spec.materialize(values));
    }

    @Whitelisted
    public boolean forget(@NonNull String credentialsId) {
        return EphemeralCredentialsProvider.get().forget(run(), credentialsId);
    }

    /** Groovy {@code ephemeralCredentials['id']} sugar - same as {@link #find}. */
    @Whitelisted
    @CheckForNull
    public Credentials getAt(@NonNull String credentialsId) {
        return find(credentialsId);
    }

    /** Groovy {@code ephemeralCredentials['id'] = credentials} sugar - same as {@link #put}. */
    @Whitelisted
    public void putAt(@NonNull String credentialsId, @NonNull Credentials credentials) {
        put(credentialsId, credentials);
    }

    /** Same as {@link #has} - offered under the {@code Map}-conventional name too. */
    @Whitelisted
    public boolean containsKey(@NonNull String credentialsId) {
        return has(credentialsId);
    }

    /** Same as {@link #forget} - offered under the {@code Map}-conventional name too. */
    @Whitelisted
    public boolean remove(@NonNull String credentialsId) {
        return forget(credentialsId);
    }
}
