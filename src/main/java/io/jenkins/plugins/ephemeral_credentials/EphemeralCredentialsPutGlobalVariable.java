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
import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/**
 * <p>Registers {@code ephemeralCredentialsPut(...)} as a global step (no
 * {@code @Library} import needed), caching a credential into {@link
 * EphemeralCredentialsProvider} for the current run directly - the
 * pipeline-facing equivalent of {@link EphemeralCredentialsAccessor#put}.
 * Two call shapes, both keyword-argument only (deliberately - see below):</p>
 * <ul>
 *     <li>{@code ephemeralCredentialsPut(id: 'FOO', credentials: someCredentials)}
 *     - caches an already-built {@link Credentials} object directly.</li>
 *     <li>{@code ephemeralCredentialsPut(spec: ephemeralUsernamePassword(id:
 *     'FOO', description: '...'), values: [username: 'u', password: 'p'])} -
 *     materializes {@code spec} from {@code values} first, the same shape
 *     {@code input}'s answer would have been, letting a pipeline
 *     pre-populate a credential from data it already has (fetched
 *     mid-pipeline, computed, ...) without ever pausing on {@code input}.</li>
 * </ul>
 *
 * <p>Both shapes are handled by one single-{@code Map}-argument {@code
 * doCall} rather than two overloaded {@code doCall} methods distinguished
 * by parameter count/type - {@link Closure} subclasses do not reliably
 * support Java-style overload resolution for {@code doCall} the way a
 * plain Java class would; an earlier version with two separate overloads
 * silently dispatched calls incorrectly (confirmed against a real embedded
 * Jenkins: the {@code id:}/{@code credentials:} shape appeared to succeed -
 * no exception - but cached nothing findable afterwards). One {@code
 * doCall(Map)}, branching on which keys are present, is the same shape
 * every other {@code GlobalVariable} factory in this plugin already uses.</p>
 *
 * @see EphemeralCredentialsAccessor
 */
@Extension
public class EphemeralCredentialsPutGlobalVariable extends GlobalVariable {

    @NonNull
    @Override
    public String getName() {
        return "ephemeralCredentialsPut";
    }

    @NonNull
    @Override
    public Object getValue(@NonNull CpsScript script) {
        return new Factory(script);
    }

    /**
     * Named (not anonymous) so {@code doCall} - invoked reflectively by
     * {@link Closure#call}, not from visible Java code - doesn't trip
     * SpotBugs' {@code UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS}, which is
     * specifically scoped to anonymous classes.
     */
    private static final class Factory extends Closure<Void> {
        private static final long serialVersionUID = 1L;

        Factory(Object owner) {
            super(owner);
        }

        @SuppressWarnings("unused")
        public Void doCall(Map<String, Object> args) {
            Object specArg = args.get("spec");
            if (specArg instanceof EphemeralCredentialSpec) {
                EphemeralCredentialSpec spec = (EphemeralCredentialSpec) specArg;
                Object valuesArg = args.get("values");
                @SuppressWarnings("unchecked")
                Map<String, Object> values = valuesArg instanceof Map ? (Map<String, Object>) valuesArg : Map.of();
                EphemeralCredentialsAccessor.forCurrentRun("ephemeralCredentialsPut")
                        .put(spec, values);
                return null;
            }
            String id = String.valueOf(args.get("id"));
            Object credentials = args.get("credentials");
            if (!(credentials instanceof Credentials)) {
                throw new IllegalArgumentException("ephemeralCredentialsPut(id:, credentials:) requires a real "
                        + "Credentials object, or use ephemeralCredentialsPut(spec:, values:) to materialize one "
                        + "from raw values");
            }
            EphemeralCredentialsAccessor.forCurrentRun("ephemeralCredentialsPut")
                    .put(id, (Credentials) credentials);
            return null;
        }
    }
}
