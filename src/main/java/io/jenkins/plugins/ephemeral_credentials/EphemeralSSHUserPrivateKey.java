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

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.model.ParameterDefinition;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>SSH Username with private key. Unlike the other credential types, this one
 * pulls in a plugin (<a href="https://plugins.jenkins.io/ssh-credentials/">SSH
 * Credentials</a>) beyond what this plugin otherwise needs - so it's declared
 * as an {@code optional} Maven/HPI dependency: installations that never use
 * {@code ephemeralSSHUserPrivateKey} don't need it installed at all.</p>
 *
 * <p>The constructor deliberately touches {@link BasicSSHUserPrivateKey} (a
 * class literal is enough to force resolution) so that a missing plugin
 * fails immediately, with a clear message, at the point the pipeline author
 * declares this spec - not deep inside {@link #materialize} after a human
 * has already answered an {@code input} prompt for nothing.</p>
 *
 * @see EphemeralCredentialSpec
 * @see BasicSSHUserPrivateKey
 */
public class EphemeralSSHUserPrivateKey extends EphemeralCredentialSpec {

    private static final long serialVersionUID = 1L;

    /**
     * Matches a PEM block regardless of whether its base64 body still has
     * line breaks or not - see {@link #reconstructPem(String)}.
     */
    private static final Pattern PEM_BLOCK =
            Pattern.compile("(-----BEGIN .+?-----)(.*)(-----END .+?-----)", Pattern.DOTALL);

    public EphemeralSSHUserPrivateKey(String id, String description) {
        super(id, description);
        try {
            ensureAvailable();
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException(
                    "ephemeralSSHUserPrivateKey('" + id + "') requires the \"SSH Credentials\" plugin "
                            + "(https://plugins.jenkins.io/ssh-credentials/) to be installed",
                    e);
        }
    }

    private static void ensureAvailable() {
        // The class literal itself is what forces resolution; wrapped in
        // requireNonNull (always trivially true) rather than assigned to an
        // unused local, which SpotBugs flags as a dead store.
        Objects.requireNonNull(BasicSSHUserPrivateKey.class);
    }

    @Override
    public List<ParameterDefinition> inputParameters() {
        return Arrays.asList(
                new StringParameterDefinition("username", "", "SSH username for credential '" + getId() + "'"),
                new PasswordParameterDefinition(
                        "privateKey",
                        "",
                        "Private key (PEM) for credential '" + getId() + "' - paste as-is, including the "
                                + "BEGIN/END markers; line breaks are reconstructed automatically"),
                new PasswordParameterDefinition(
                        "passphrase",
                        "",
                        "Private key passphrase for credential '" + getId() + "' (leave blank if none)"));
    }

    @Override
    public Credentials materialize(Map<String, Object> answers) {
        String username = String.valueOf(answers.get("username"));
        String privateKey = reconstructPem(String.valueOf(answers.get("privateKey")));
        String passphrase = String.valueOf(answers.getOrDefault("passphrase", ""));
        BasicSSHUserPrivateKey.DirectEntryPrivateKeySource source =
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKey);
        return new BasicSSHUserPrivateKey(
                CredentialsScope.GLOBAL,
                getId(),
                username,
                source,
                passphrase.isEmpty() ? null : passphrase,
                getDescription());
    }

    /**
     * Collected via {@link PasswordParameterDefinition} rather than a plain
     * text/textarea field, so that Jenkins encrypts the submitted value at
     * rest (see the class javadoc) - but a single-line HTML password field
     * strips embedded line breaks per the HTML value-sanitization algorithm,
     * so a pasted multi-line PEM key arrives with its BEGIN/END markers and
     * base64 body all run together. Reconstructs a syntactically valid PEM
     * block from that: a PEM's base64 body needs no particular line width -
     * confirmed empirically (ssh-keygen/openssl parse a single-unwrapped-line
     * body identically to the traditional 64/70-column-wrapped form) - so
     * this just needs real newlines immediately around the BEGIN/END marker
     * lines, not any specific wrapping of the body itself.
     */
    private static String reconstructPem(String submitted) {
        Matcher m = PEM_BLOCK.matcher(submitted.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Private key does not look like a PEM block (missing '-----BEGIN ...-----'/'-----END ...-----' markers)");
        }
        return m.group(1) + "\n" + m.group(2).replaceAll("\\s+", "") + "\n" + m.group(3) + "\n";
    }
}
