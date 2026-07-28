package com.myprovys.ci.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

import java.util.Map;

/**
 * Registers {@code ephemeralCertificate(id: ..., description: ...)}.
 *
 * @see EphemeralCertificate
 * @see EphemeralCredentialSpec
 */
@Extension
public class EphemeralCertificateGlobalVariable extends GlobalVariable {

    @NonNull
    @Override
    public String getName() {
        return "ephemeralCertificate";
    }

    @NonNull
    @Override
    public Object getValue(@NonNull CpsScript script) {
        return new Closure<EphemeralCredentialSpec>(script) {
            @SuppressWarnings("unused")
            public EphemeralCredentialSpec doCall(Map<String, Object> args) {
                return new EphemeralCertificate(
                        String.valueOf(args.get("id")),
                        args.get("description") == null ? null : String.valueOf(args.get("description")));
            }
        };
    }
}
