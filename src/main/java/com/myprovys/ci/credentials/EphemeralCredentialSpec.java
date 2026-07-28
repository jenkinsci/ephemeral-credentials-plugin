package com.myprovys.ci.credentials;

import com.cloudbees.plugins.credentials.Credentials;
import hudson.model.ParameterDefinition;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Describes one credential a {@code withEphemeralCredentials} block may need
 * to resolve interactively: which {@code input} parameters to ask the user
 * for, and how to turn the collected answers into a concrete
 * {@link Credentials} object. Concrete subtypes are constructed from pipeline
 * script via the matching factory global variable, e.g.
 * {@code ephemeralUsernamePassword(id: 'FOO', description: '...')} - the same
 * ergonomics as {@code usernamePassword(...)} inside {@code withCredentials}.
 *
 * <p>Plain data/logic, never itself invokes a pipeline step, so unlike
 * {@code WithEphemeralCredentials} it needs no special CPS treatment.
 */
public abstract class EphemeralCredentialSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String description;

    protected EphemeralCredentialSpec(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Parameters to pass to the {@code input} step when this credential is
     * missing. {@code input}'s own {@code message} is the spec's
     * {@link #getDescription()} (falling back to a generic default), set by
     * the caller - these are just the value-collecting fields.
     */
    public abstract List<ParameterDefinition> inputParameters();

    /**
     * Builds the credential from the {@code input} step's answers, keyed by
     * the parameter names returned from {@link #inputParameters()}.
     */
    public abstract Credentials materialize(Map<String, Object> answers);
}
