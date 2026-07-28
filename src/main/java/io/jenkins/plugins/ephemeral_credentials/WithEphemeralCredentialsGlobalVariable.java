/*
 * The MIT License
 *
 * Copyright (c) 2026, Jim Klimov, PROVYS Technologies a.s.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.jenkins.plugins.ephemeral_credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import hudson.Extension;
import hudson.model.Run;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/**
 * <p>Registers {@code withEphemeralCredentials} as a global pipeline step,
 * available in any Pipeline job once this plugin is installed - no
 * {@code @Library} import needed, same as {@code lock} or {@code input}.</p>
 *
 * <p>The actual implementation ({@code WithEphemeralCredentials.groovy})
 * ships as a plugin resource, not precompiled, and is parsed on demand
 * through the calling script's own {@link GroovyClassLoader} - the same
 * technique {@code docker-workflow-plugin}'s {@code DockerDSL} uses for its
 * {@code Docker.groovy} - so it gets the same CPS transformation as a
 * shared-library vars/src script, which is what makes its calls to
 * {@code lock}/{@code input} safe. The source is read via this class's own
 * classloader (which can always find our plugin's resources) and handed
 * directly to {@code parseClass}, rather than relying on the CPS
 * classloader's {@code loadClass} to independently discover a {@code .groovy}
 * resource across plugin boundaries - that discovery path isn't guaranteed
 * to search other plugins' jars.</p>
 *
 * @see EphemeralCredentialSpec
 * @see WithEphemeralCredentials
 */
@Extension
public class WithEphemeralCredentialsGlobalVariable extends GlobalVariable {

    private static final String RESOURCE = "io/jenkins/plugins/ephemeral_credentials/WithEphemeralCredentials.groovy";
    private static final String CLASS_NAME = "io.jenkins.plugins.ephemeral_credentials.WithEphemeralCredentials";

    @NonNull
    @Override
    public String getName() {
        return "withEphemeralCredentials";
    }

    @NonNull
    @Override
    public Object getValue(@NonNull CpsScript script) throws Exception {
        Binding binding = script.getBinding();
        Object instance;
        if (binding.hasVariable(getName())) {
            instance = binding.getVariable(getName());
        } else {
            // Resolved here, in plain trusted Java, rather than inside the
            // CPS-transformed Groovy via `currentBuild.rawBuild` - that
            // getter is sandbox-restricted, and whether it's callable
            // depends on whether the *calling* Jenkinsfile happens to be
            // trusted, which we can't assume.
            Run<?, ?> run = CpsRuns.current();
            if (run == null) {
                throw new IllegalStateException(
                        "withEphemeralCredentials can only be used from within a running Pipeline build");
            }

            GroovyClassLoader gcl = (GroovyClassLoader) script.getClass().getClassLoader();
            Class<?> clazz;
            try {
                // Already parsed for an earlier build using this same
                // classloader (e.g. a fresh script re-executing after a
                // Jenkins restart) - reuse it rather than redefining.
                clazz = gcl.loadClass(CLASS_NAME, false, false);
            } catch (ClassNotFoundException e) {
                clazz = gcl.parseClass(readSource(), "WithEphemeralCredentials.groovy");
            }
            instance =
                    clazz.getConstructor(CpsScript.class, String.class).newInstance(script, run.getExternalizableId());
            binding.setVariable(getName(), instance);
        }
        return instance;
    }

    private static String readSource() throws IOException {
        try (InputStream in =
                WithEphemeralCredentialsGlobalVariable.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Resource not found on plugin classpath: " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
