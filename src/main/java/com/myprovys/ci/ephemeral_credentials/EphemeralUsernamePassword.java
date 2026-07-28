package com.myprovys.ci.ephemeral_credentials;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.ParameterDefinition;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Two strings: a username and a password.
 *
 * @see EphemeralCredentialSpec
 * @see UsernamePasswordCredentialsImpl
 */
public class EphemeralUsernamePassword extends EphemeralCredentialSpec {

    private static final long serialVersionUID = 1L;

    public EphemeralUsernamePassword(String id, String description) {
        super(id, description);
    }

    @Override
    public List<ParameterDefinition> inputParameters() {
        return Arrays.asList(
                new StringParameterDefinition("username", "", "Username for credential '" + getId() + "'"),
                new PasswordParameterDefinition("password", "", "Password for credential '" + getId() + "'"));
    }

    @Override
    public Credentials materialize(Map<String, Object> answers) {
        String username = String.valueOf(answers.get("username"));
        String password = String.valueOf(answers.get("password"));
        try {
            return new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL, getId(), getDescription(), username, password);
        } catch (Exception e) {
            // Newer ephemeral_credentials-plugin releases declare this constructor as
            // throwing Descriptor.FormException (form-validation feedback
            // for the "Add Credentials" web UI - not relevant here). Caught
            // as the broad Exception type, not FormException by name, so
            // this keeps compiling against older ephemeral_credentials-plugin
            // releases too, where the constructor doesn't declare throwing
            // anything at all.
            throw new IllegalStateException("Failed to build credential '" + getId() + "'", e);
        }
    }
}
