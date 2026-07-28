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
import hudson.model.ParameterDefinition;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * <p>Describes one credential a {@code withEphemeralCredentials} block may need
 * to resolve interactively: which {@code input} parameters to ask the user
 * for, and how to turn the collected answers into a concrete
 * {@link Credentials} object. Concrete subtypes are constructed from pipeline
 * script via the matching factory global variable, e.g.
 * {@code ephemeralUsernamePassword(id: 'FOO', description: '...')} - the same
 * ergonomics as {@code usernamePassword(...)} inside {@code withCredentials}.</p>
 *
 * <p>Plain data/logic, never itself invokes a pipeline step, so unlike
 * {@code WithEphemeralCredentials} it needs no special CPS treatment
 * and is {@link Serializable}.</p>
 */
public abstract class EphemeralCredentialSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String description;

    protected EphemeralCredentialSpec(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Parameters to pass to the {@code input} step when this credential is
     * missing. {@code input}'s own {@code message} is the spec's
     * {@link #getDescription()} (falling back to a generic default), set by
     * the caller - these are just the value-collecting fields.
     */
    public abstract List<ParameterDefinition> inputParameters();

    /**
     * Builds the credential from the {@code input} step's answers, keyed by
     * the parameter names returned from {@link #inputParameters()}.
     */
    public abstract Credentials materialize(Map<String, Object> answers);
}
