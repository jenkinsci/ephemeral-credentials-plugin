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
package com.myprovys.ci.ephemeral_credentials;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.model.ParameterDefinition;
import hudson.model.PasswordParameterDefinition;
import hudson.util.Secret;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

/**
 * Represents ephemeral secret text credential.
 *
 * @see EphemeralCredentialSpec
 * @see StringCredentialsImpl
 */
public class EphemeralSecretText extends EphemeralCredentialSpec {

    private static final long serialVersionUID = 1L;

    public EphemeralSecretText(String id, String description) {
        super(id, description);
    }

    @Override
    public List<ParameterDefinition> inputParameters() {
        return Collections.singletonList(
                new PasswordParameterDefinition("secret", "", "Secret value for credential '" + getId() + "'"));
    }

    @Override
    public Credentials materialize(Map<String, Object> answers) {
        Secret secret = Secret.fromString(String.valueOf(answers.get("secret")));
        return new StringCredentialsImpl(CredentialsScope.GLOBAL, getId(), getDescription(), secret);
    }
}
