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
import com.cloudbees.plugins.credentials.SecretBytes;
import hudson.model.ParameterDefinition;
import hudson.model.TextParameterDefinition;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;

/**
 * Secret file. There's no clean way to have {@code input} collect a genuine
 * file upload here -- {@code FileParameterDefinition} writes the uploaded
 * file to disk as part of its own normal handling, in direct tension with
 * this plugin's "never persisted" design -- so the value is collected as
 * pasted base64-encoded text and served from memory instead.
 *
 * @see FileCredentialsImpl
 * @see EphemeralCredentialSpec
 */
public class EphemeralSecretFile extends EphemeralCredentialSpec {

    private static final long serialVersionUID = 1L;

    private final String fileName;

    public EphemeralSecretFile(String id, String description, String fileName) {
        super(id, description);
        this.fileName = (fileName == null || fileName.isEmpty()) ? id : fileName;
    }

    public String getFileName() {
        return fileName;
    }

    @Override
    public List<ParameterDefinition> inputParameters() {
        return Collections.singletonList(new TextParameterDefinition(
                "contentBase64", "", "Base64-encoded content for the secret file credential '" + getId() + "'"));
    }

    @Override
    public Credentials materialize(Map<String, Object> answers) {
        byte[] content = Base64.getDecoder()
                .decode(String.valueOf(answers.get("contentBase64")).trim());
        return new FileCredentialsImpl(
                CredentialsScope.GLOBAL, getId(), getDescription(), fileName, SecretBytes.fromBytes(content));
    }
}
