package com.myprovys.ci.ephemeral_credentials;

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
        return new Factory(script);
    }

    /**
     * Named (not anonymous) so {@code doCall} - invoked reflectively by
     * {@link Closure#call}, not from visible Java code - doesn't trip
     * SpotBugs' {@code UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS}, which is
     * specifically scoped to anonymous classes.
     */
    private static final class Factory extends Closure<EphemeralCredentialSpec> {
        Factory(Object owner) {
            super(owner);
        }

        @SuppressWarnings("unused")
        public EphemeralCredentialSpec doCall(Map<String, Object> args) {
            return new EphemeralSecretText(
                    String.valueOf(args.get("id")),
                    args.get("description") == null ? null : String.valueOf(args.get("description")));
        }
    }
}
