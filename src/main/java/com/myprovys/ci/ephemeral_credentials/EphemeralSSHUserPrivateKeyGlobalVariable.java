package com.myprovys.ci.ephemeral_credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/**
 * Registers {@code ephemeralSSHUserPrivateKey(id: ..., description: ...)} -
 * see {@link EphemeralUsernamePasswordGlobalVariable} for the general shape.
 * If the optional "SSH Credentials" plugin isn't installed,
 * {@link EphemeralSSHUserPrivateKey}'s constructor throws a clear
 * {@link IllegalStateException} that surfaces as a normal pipeline failure -
 * no try/catch needed here.
 *
 * @see EphemeralCredentialSpec
 * @see EphemeralSSHUserPrivateKey
 */
@Extension
public class EphemeralSSHUserPrivateKeyGlobalVariable extends GlobalVariable {

    @NonNull
    @Override
    public String getName() {
        return "ephemeralSSHUserPrivateKey";
    }

    @NonNull
    @Override
    public Object getValue(@NonNull CpsScript script) {
        return new Closure<EphemeralCredentialSpec>(script) {
            @SuppressWarnings("unused")
            public EphemeralCredentialSpec doCall(Map<String, Object> args) {
                return new EphemeralSSHUserPrivateKey(
                        String.valueOf(args.get("id")),
                        args.get("description") == null ? null : String.valueOf(args.get("description")));
            }
        };
    }
}
