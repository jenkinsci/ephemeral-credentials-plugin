package com.myprovys.ci.ephemeral_credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/**
 * <p>Registers {@code ephemeralUsernamePassword(id: ..., description: ...)} as
 * a global factory function, usable inside a {@code withEphemeralCredentials}
 * spec list - the same DSL ergonomics as {@code usernamePassword(...)} inside
 * {@code withCredentials}, just producing an {@link EphemeralUsernamePassword}
 * instead of binding env vars directly.</p>
 *
 * <p>Unlike {@code WithEphemeralCredentials}, this never invokes a pipeline
 * step itself - it just builds a plain data object - so a precompiled Java
 * {@link Closure} is fine; no CPS transformation is needed here.</p>
 *
 * @see EphemeralCredentialSpec
 * @see EphemeralUsernamePassword
 */
@Extension
public class EphemeralUsernamePasswordGlobalVariable extends GlobalVariable {

    @NonNull
    @Override
    public String getName() {
        return "ephemeralUsernamePassword";
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
            return new EphemeralUsernamePassword(
                    String.valueOf(args.get("id")),
                    args.get("description") == null ? null : String.valueOf(args.get("description")));
        }
    }
}
