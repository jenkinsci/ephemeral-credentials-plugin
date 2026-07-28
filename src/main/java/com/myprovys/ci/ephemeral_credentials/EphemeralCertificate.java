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
import com.cloudbees.plugins.credentials.impl.CertificateCredentialsImpl;
import hudson.model.ParameterDefinition;
import hudson.model.PasswordParameterDefinition;
import hudson.model.TextParameterDefinition;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Certificate (PKCS#12 keystore + password). Like {@link EphemeralSecretFile},
 * the keystore is collected as pasted base64 text rather than a real file
 * upload - see that class's javadoc for why. Decoded straight into a
 * {@link SecretBytes} and handed to {@code UploadedKeyStoreSource}'s
 * {@code SecretBytes}-accepting constructor, rather than its String
 * overload, which does its own base64 handling internally - passing the
 * already-decoded bytes avoids any ambiguity about what that overload
 * expects.
 *
 * @see CertificateCredentialsImpl
 * @see EphemeralCredentialSpec
 */
public class EphemeralCertificate extends EphemeralCredentialSpec {

    private static final long serialVersionUID = 1L;

    public EphemeralCertificate(String id, String description) {
        super(id, description);
    }

    @Override
    public List<ParameterDefinition> inputParameters() {
        return Arrays.asList(
                new TextParameterDefinition(
                        "keystoreBase64", "", "Base64-encoded PKCS#12 keystore for credential '" + getId() + "'"),
                new PasswordParameterDefinition("password", "", "Keystore password for credential '" + getId() + "'"));
    }

    @Override
    public Credentials materialize(Map<String, Object> answers) {
        byte[] keystoreBytes = Base64.getDecoder()
                .decode(String.valueOf(answers.get("keystoreBase64")).trim());
        String password = String.valueOf(answers.getOrDefault("password", ""));
        CertificateCredentialsImpl.UploadedKeyStoreSource source =
                new CertificateCredentialsImpl.UploadedKeyStoreSource(SecretBytes.fromBytes(keystoreBytes));
        return new CertificateCredentialsImpl(CredentialsScope.GLOBAL, getId(), getDescription(), password, source);
    }
}
