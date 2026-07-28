package com.example.jsl

import com.cloudbees.plugins.credentials.Credentials
import com.cloudbees.plugins.credentials.CredentialsScope
import com.myprovys.ci.ephemeral_credentials.EphemeralCredentialSpec
import hudson.model.ParameterDefinition
import hudson.model.PasswordParameterDefinition
import hudson.util.Secret
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl

/**
 * Example custom credential type, defined entirely in a (trusted, global)
 * shared library - not in the EphemeralCredentialsProvider plugin itself.
 * Extends the plugin's public EphemeralCredentialSpec directly, the same
 * way any of the plugin's own built-in specs do. Reuses StringCredentialsImpl
 * (the same type EphemeralSecretText already wraps) so this only needs to
 * prove the extension mechanism, not add a genuinely new Credentials
 * implementation.
 *
 * Since global libraries are trusted (unsandboxed), this class - and
 * vars/myCorpApiToken.groovy alongside it - can call any Java method
 * freely, with none of the script-security whitelist requirements
 * WithEphemeralCredentials.groovy itself is subject to (that file is
 * compiled through the *calling build's* classloader and therefore
 * inherits whatever sandbox status that build's Jenkinsfile has; this
 * class is compiled as part of a trusted library instead).
 */
class MyCorpApiTokenSpec extends EphemeralCredentialSpec {

    MyCorpApiTokenSpec(String id, String description) {
        super(id, description)
    }

    @Override
    List<ParameterDefinition> inputParameters() {
        return [new PasswordParameterDefinition("token", "", "MyCorp API token for credential '${getId()}'")]
    }

    @Override
    Credentials materialize(Map<String, Object> answers) {
        Secret secret = Secret.fromString(String.valueOf(answers.get("token")))
        return new StringCredentialsImpl(CredentialsScope.GLOBAL, getId(), getDescription(), secret)
    }
}
