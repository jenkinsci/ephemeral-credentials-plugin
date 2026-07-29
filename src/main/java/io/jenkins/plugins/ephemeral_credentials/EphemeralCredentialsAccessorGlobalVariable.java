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
import groovy.lang.Binding;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/**
 * <p>Registers {@code ephemeralCredentials} as a global variable (no
 * {@code @Library} import needed), resolving to an {@link
 * EphemeralCredentialsAccessor} bound to the current run - lets a pipeline
 * write {@code ephemeralCredentials['FOO'] = someCredentials} /
 * {@code ephemeralCredentials['FOO']} /
 * {@code ephemeralCredentials.containsKey('FOO')} /
 * {@code ephemeralCredentials.remove('FOO')} directly.</p>
 *
 * <p>Cached in the script's {@link Binding} after first resolution, the
 * same way {@link WithEphemeralCredentialsGlobalVariable} caches its own
 * instance - {@link EphemeralCredentialsAccessor} only holds a plain {@code
 * runId} String, so re-resolving it repeatedly would be harmless, but
 * there's no reason to redo the {@link CpsRuns#current()} lookup on every
 * single {@code ephemeralCredentials[...]} access within one script run.</p>
 *
 * @see EphemeralCredentialsAccessor
 */
@Extension
public class EphemeralCredentialsAccessorGlobalVariable extends GlobalVariable {

    @NonNull
    @Override
    public String getName() {
        return "ephemeralCredentials";
    }

    @NonNull
    @Override
    public Object getValue(@NonNull CpsScript script) {
        Binding binding = script.getBinding();
        if (!binding.hasVariable(getName())) {
            binding.setVariable(getName(), EphemeralCredentialsAccessor.forCurrentRun(getName()));
        }
        return binding.getVariable(getName());
    }
}
