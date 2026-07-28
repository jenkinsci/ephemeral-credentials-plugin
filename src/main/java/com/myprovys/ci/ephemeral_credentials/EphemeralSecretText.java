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
