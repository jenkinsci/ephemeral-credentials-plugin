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
