package com.myprovys.ci.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

import java.util.Map;

/**
 * Registers {@code ephemeralUsernamePassword(id: ..., description: ...)} as
 * a global factory function, usable inside a {@code withEphemeralCredentials}
 * spec list - the same DSL ergonomics as {@code usernamePassword(...)} inside
 * {@code withCredentials}, just producing an {@link EphemeralUsernamePassword}
 * instead of binding env vars directly.
 *
 * <p>Unlike {@code WithEphemeralCredentials}, this never invokes a pipeline
 * step itself - it just builds a plain data object - so a precompiled Java
 * {@link Closure} is fine; no CPS transformation is needed here.
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
        return new Closure<EphemeralCredentialSpec>(script) {
            @SuppressWarnings("unused")
            public EphemeralCredentialSpec doCall(Map<String, Object> args) {
                return new EphemeralUsernamePassword(
                        String.valueOf(args.get("id")),
                        args.get("description") == null ? null : String.valueOf(args.get("description")));
            }
        };
    }
}
