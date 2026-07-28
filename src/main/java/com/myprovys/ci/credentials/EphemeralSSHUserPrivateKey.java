package com.myprovys.ci.credentials;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.model.ParameterDefinition;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    public EphemeralSSHUserPrivateKey(String id, String description) {
        super(id, description);
        try {
            ensureAvailable();
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException(
                    "ephemeralSSHUserPrivateKey('" + id + "') requires the \"SSH Credentials\" plugin "
                            + "(https://plugins.jenkins.io/ssh-credentials/) to be installed", e);
        }
    }

    private static void ensureAvailable() {
        Class<?> ignored = BasicSSHUserPrivateKey.class;
    }

    @Override
    public List<ParameterDefinition> inputParameters() {
        return Arrays.asList(
                new StringParameterDefinition("username", "", "SSH username for credential '" + getId() + "'"),
                new TextParameterDefinition("privateKey", "", "Private key (PEM) for credential '" + getId() + "'"),
                new PasswordParameterDefinition("passphrase", "",
                        "Private key passphrase for credential '" + getId() + "' (leave blank if none)")
        );
    }

    @Override
    public Credentials materialize(Map<String, Object> answers) {
        String username = String.valueOf(answers.get("username"));
        String privateKey = String.valueOf(answers.get("privateKey"));
        String passphrase = String.valueOf(answers.getOrDefault("passphrase", ""));
        BasicSSHUserPrivateKey.DirectEntryPrivateKeySource source =
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKey);
        return new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, getId(), username, source,
                passphrase.isEmpty() ? null : passphrase, getDescription());
    }
}
