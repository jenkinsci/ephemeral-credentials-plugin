package com.myprovys.ci.ephemeral_credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/**
 * Registers {@code ephemeralSecretFile(id: ..., description: ..., fileName: ...)}
 * - {@code fileName} is optional and defaults to {@code id}.
 *
 * @see EphemeralSecretFile
 * @see EphemeralCredentialSpec
 */
@Extension
public class EphemeralSecretFileGlobalVariable extends GlobalVariable {

    @NonNull
    @Override
    public String getName() {
        return "ephemeralSecretFile";
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
            return new EphemeralSecretFile(
                    String.valueOf(args.get("id")),
                    args.get("description") == null ? null : String.valueOf(args.get("description")),
                    args.get("fileName") == null ? null : String.valueOf(args.get("fileName")));
        }
    }
}
