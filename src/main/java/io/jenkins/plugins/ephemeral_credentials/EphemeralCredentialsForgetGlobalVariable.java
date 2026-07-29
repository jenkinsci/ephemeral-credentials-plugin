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

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/**
 * Registers {@code ephemeralCredentialsForget(id)} as a global step (no
 * {@code @Library} import needed) - drops the credential cached under
 * {@code id} for the current run, if any, and returns whether one was
 * actually present. Useful for proactively purging a sensitive one-time-use
 * credential before the run ends, rather than waiting for {@link
 * EphemeralCredentialsRunListener}'s whole-run cleanup. The pipeline-facing
 * equivalent of {@link EphemeralCredentialsAccessor#forget}.
 *
 * @see EphemeralCredentialsAccessor
 */
@Extension
public class EphemeralCredentialsForgetGlobalVariable extends GlobalVariable {

    @NonNull
    @Override
    public String getName() {
        return "ephemeralCredentialsForget";
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
    private static final class Factory extends Closure<Boolean> {
        private static final long serialVersionUID = 1L;

        Factory(Object owner) {
            super(owner);
        }

        @SuppressWarnings("unused")
        public Boolean doCall(String id) {
            return EphemeralCredentialsAccessor.forCurrentRun("ephemeralCredentialsForget")
                    .forget(id);
        }
    }
}
