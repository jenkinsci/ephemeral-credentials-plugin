package com.myprovys.ci.credentials;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SecretBytes;
import hudson.model.ParameterDefinition;
import hudson.model.TextParameterDefinition;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        return Collections.singletonList(
                new TextParameterDefinition("contentBase64", "",
                        "Base64-encoded content for the secret file credential '" + getId() + "'")
        );
    }

    @Override
    public Credentials materialize(Map<String, Object> answers) {
        byte[] content = Base64.getDecoder().decode(String.valueOf(answers.get("contentBase64")).trim());
        return new FileCredentialsImpl(CredentialsScope.GLOBAL, getId(), getDescription(), fileName,
                SecretBytes.fromBytes(content));
    }
}
