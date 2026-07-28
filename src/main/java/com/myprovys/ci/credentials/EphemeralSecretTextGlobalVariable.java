package com.myprovys.ci.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/**
 * Registers {@code ephemeralSecretText(id: ..., description: ...)} as a
 * global factory function - see {@link EphemeralUsernamePasswordGlobalVariable}
 * for the general shape and why a precompiled Closure is fine here.
 *
 * @see EphemeralSecretText
 * @see EphemeralCredentialSpec
 */
@Extension
public class EphemeralSecretTextGlobalVariable extends GlobalVariable {

    @NonNull
    @Override
    public String getName() {
        return "ephemeralSecretText";
    }

    @NonNull
    @Override
    public Object getValue(@NonNull CpsScript script) {
        return new Closure<EphemeralCredentialSpec>(script) {
            @SuppressWarnings("unused")
            public EphemeralCredentialSpec doCall(Map<String, Object> args) {
                return new EphemeralSecretText(
                        String.valueOf(args.get("id")),
                        args.get("description") == null ? null : String.valueOf(args.get("description")));
            }
        };
    }
}
