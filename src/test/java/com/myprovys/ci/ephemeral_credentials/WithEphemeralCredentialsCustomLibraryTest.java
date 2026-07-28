package com.myprovys.ci.ephemeral_credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Illustrates and confirms that a completely custom credential type can be
 * added to {@code withEphemeralCredentials} without touching this plugin at
 * all - by defining an {@link EphemeralCredentialSpec} subclass and its
 * factory function in an ordinary (trusted, global) shared library instead.
 *
 * <p>The library content lives under {@code src/test/resources/com/myprovys/
 * ci/ephemeral_credentials/{vars,src}/} as genuine {@code .groovy} files (not embedded
 * Java string literals), copied into a throwaway local git repo and loaded
 * the same way a real Jenkins controller would load a globally-configured
 * library. It defines:
 * <ul>
 *   <li>{@code vars/myCorpApiToken.groovy} - an independently-named factory,
 *       parallel to this plugin's own {@code ephemeralSecretText};</li>
 *   <li>{@code src/com/example/jsl/MyCorpApiTokenSpec.groovy} - an
 *       independently-named {@link EphemeralCredentialSpec} subclass,
 *       reusing {@code StringCredentialsImpl} (the same type
 *       {@code EphemeralSecretText} already wraps) purely to prove the
 *       extension mechanism, not to add a genuinely new credential type.</li>
 * </ul>
 *
 * <p>{@link GitSampleRepoRule} - the standard way to test SCM-loaded shared
 * libraries, matching the pattern the shared-library plugin's own test suite
 * ({@code GlobalLibrariesTest}) uses for exactly this feature - is a classic
 * JUnit 4 {@code @Rule} with no JUnit 5 form, and this project's enforcer
 * config bans any {@code org.junit.*} import in test code (including {@code
 * @Rule} itself), so its {@code before()}/{@code after()} are driven directly
 * from {@code @BeforeEach}/{@code @AfterEach} here instead of through a rule
 * mechanism. {@link JenkinsRule} itself still comes from the ordinary
 * {@code @WithJenkins} parameter injection this project's other tests use.
 */
@WithJenkins
class WithEphemeralCredentialsCustomLibraryTest {

    private final GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    @BeforeEach
    void startSampleRepo() throws Throwable {
        sampleRepo.before();
    }

    @AfterEach
    void stopSampleRepo() {
        sampleRepo.after();
    }

    @Test
    @Timeout(600)
    void customCredentialTypeDefinedInSharedLibrary(JenkinsRule j) throws Exception {
        sampleRepo.init();
        copyResourceIntoRepo("vars/myCorpApiToken.groovy", "vars/myCorpApiToken.groovy");
        copyResourceIntoRepo(
                "src/com/example/jsl/MyCorpApiTokenSpec.groovy", "src/com/example/jsl/MyCorpApiTokenSpec.groovy");
        sampleRepo.git("add", "vars", "src");
        sampleRepo.git("commit", "--message=init");

        // A globally-configured library is trusted (unsandboxed) by design -
        // only an administrator can configure one - which is exactly what
        // lets MyCorpApiTokenSpec call any Java method freely, with none of
        // the script-security whitelist requirements
        // WithEphemeralCredentials.groovy itself is subject to.
        LibraryConfiguration lib =
                new LibraryConfiguration("mylib", new SCMSourceRetriever(new GitSCMSource(sampleRepo.toString())));
        GlobalLibraries.get().setLibraries(Collections.singletonList(lib));

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "custom-library-credential");
        p.setDefinition(new CpsFlowDefinition(
                String.join(
                        "\n",
                        "@Library('mylib@master') _",
                        "pipeline {",
                        "  agent any",
                        "  stages {",
                        "    stage('use') {",
                        "      steps {",
                        "        withEphemeralCredentials([myCorpApiToken(id: 'API_TOKEN', description: 'MyCorp API token')]) {",
                        "          withCredentials([string(credentialsId: 'API_TOKEN', variable: 'TOKEN')]) {",
                        "            echo \"TOKEN_LEN:${TOKEN.length()}\"",
                        "          }",
                        "        }",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));

        WorkflowRun run = p.scheduleBuild2(0).waitForStart();

        String token = "s3cr3t-token-value";
        InputStepExecution pause = waitForInput(run);
        assertEquals("MyCorp API token", pause.getInput().getMessage());
        pause.proceed(Collections.singletonMap("token", token));

        j.assertBuildStatusSuccess(j.waitForCompletion(run));
        j.assertLogContains("TOKEN_LEN:" + token.length(), run);
        j.assertLogNotContains(token, run);
    }

    private void copyResourceIntoRepo(String resourcePath, String repoRelativePath) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Test resource not found: " + resourcePath);
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            sampleRepo.write(repoRelativePath, content);
        }
    }

    private static InputStepExecution waitForInput(WorkflowRun run) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            InputAction action = run.getAction(InputAction.class);
            if (action != null) {
                List<InputStepExecution> executions = action.getExecutions();
                if (!executions.isEmpty()) {
                    return executions.get(0);
                }
            }
            if (!run.isBuilding()) {
                throw new AssertionError("Build already finished (" + run.getResult()
                        + ") without ever pausing on input(). Log:\n" + String.join("\n", run.getLog(500)));
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for the pipeline to pause on input(). Log so far:\n"
                + String.join("\n", run.getLog(500)));
    }
}
