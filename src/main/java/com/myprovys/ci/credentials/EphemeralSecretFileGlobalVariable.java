package com.myprovys.ci.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

import java.util.Map;

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
        return new Closure<EphemeralCredentialSpec>(script) {
            @SuppressWarnings("unused")
            public EphemeralCredentialSpec doCall(Map<String, Object> args) {
                return new EphemeralSecretFile(
                        String.valueOf(args.get("id")),
                        args.get("description") == null ? null : String.valueOf(args.get("description")),
                        args.get("fileName") == null ? null : String.valueOf(args.get("fileName")));
            }
        };
    }
}
