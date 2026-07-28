package com.myprovys.ci.ephemeral_credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Illustrates and confirms the withEphemeralCredentials contract end to end,
 * against a real embedded Jenkins running declarative pipelines:
 * <ul>
 *   <li>an ID already resolvable via a real ephemeral_credentials store is left alone
 *       - no interactive prompt at all;</li>
 *   <li>a genuinely missing ID pauses on {@code input}, and the value
 *       supplied there reaches the wrapped {@code withCredentials} block;</li>
 *   <li>a second request for the same ID within the same run reuses the
 *       cached value instead of prompting again.</li>
 * </ul>
 */
@WithJenkins
class WithEphemeralCredentialsTest {

    @Test
    @Timeout(120)
    void alreadyRegisteredCredentialIsNeverPrompted(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "EXISTING_CRED", "pre-registered", "alice", "s3cr3t"));
        SystemCredentialsProvider.getInstance().save();

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "already-registered");
        p.setDefinition(new CpsFlowDefinition(
                String.join(
                        "\n",
                        "pipeline {",
                        "  agent any",
                        "  stages {",
                        "    stage('go') {",
                        "      steps {",
                        "        withEphemeralCredentials([ephemeralUsernamePassword(id: 'EXISTING_CRED', description: 'should not be asked')]) {",
                        "          withCredentials([usernamePassword(credentialsId: 'EXISTING_CRED', usernameVariable: 'U', passwordVariable: 'P')]) {",
                        "            echo \"GOT:${U}:${P}\"",
                        "          }",
                        "        }",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));

        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        // withCredentials masks the password in the console log, so assert
        // the username (proving the real, pre-registered credential was
        // used, not a prompted one) and that the raw secret never appears.
        j.assertLogContains("GOT:alice:", run);
        j.assertLogNotContains("s3cr3t", run);
        assertNoPendingInput(run);
    }

    @Test
    @Timeout(120)
    void missingCredentialPromptsAndReusesCacheOnSecondCall(JenkinsRule j) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "missing-then-cached");
        p.setDefinition(new CpsFlowDefinition(
                String.join(
                        "\n",
                        "pipeline {",
                        "  agent any",
                        "  stages {",
                        "    stage('first') {",
                        "      steps {",
                        "        withEphemeralCredentials([ephemeralUsernamePassword(id: 'MISSING_CRED', description: 'Provide the test credential')]) {",
                        "          withCredentials([usernamePassword(credentialsId: 'MISSING_CRED', usernameVariable: 'U', passwordVariable: 'P')]) {",
                        "            echo \"FIRST:${U}:${P}\"",
                        "          }",
                        "        }",
                        "      }",
                        "    }",
                        "    stage('second') {",
                        "      steps {",
                        "        withEphemeralCredentials([ephemeralUsernamePassword(id: 'MISSING_CRED', description: 'Provide the test credential')]) {",
                        "          withCredentials([usernamePassword(credentialsId: 'MISSING_CRED', usernameVariable: 'U', passwordVariable: 'P')]) {",
                        "            echo \"SECOND:${U}:${P}\"",
                        "          }",
                        "        }",
                        "      }",
                        "    }",
                        "    stage('third') {",
                        "      steps {",
                        "        withCredentials([usernamePassword(credentialsId: 'MISSING_CRED', usernameVariable: 'U', passwordVariable: 'P')]) {",
                        "          echo \"THIRD:${U}:${P}\"",
                        "        }",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));

        WorkflowRun run = p.scheduleBuild2(0).waitForStart();

        // First stage: nothing cached yet, so it must pause on input exactly once.
        InputStepExecution firstPause = waitForInput(run);
        assertEquals("Provide the test credential", firstPause.getInput().getMessage());
        firstPause.proceed(Map.of("username", "bob", "password", "hunter2"));

        // Second stage requests the same ID again; since it's now cached,
        // this must complete without pausing a second time.
        j.assertBuildStatusSuccess(j.waitForCompletion(run));

        // Masked by withCredentials in the console log, same as the other
        // test - assert the username reached both stages and the raw
        // secret is never printed.
        j.assertLogContains("FIRST:bob:", run);
        j.assertLogContains("SECOND:bob:", run);

        // A previously cached credential should remain resolved even without a
        // withEphemeralCredentials wrapper, as long as Jenkins did not restart.
        j.assertLogContains("THIRD:bob:", run);

        // Password should remain hidden (by ephemeral_credentials binding plugin).
        j.assertLogNotContains("hunter2", run);
    }

    @Test
    @Timeout(120)
    void sshKeySecretFileAndCertificateMaterializeCorrectly(JenkinsRule j) throws Exception {
        // Verified by consuming each credential through the *standard*
        // withCredentials bindings inside the pipeline itself, the same way
        // the other tests do - not by querying EphemeralCredentialsProvider
        // after the build finishes, since EphemeralCredentialsRunListener
        // clears its cache for the run as soon as it's done (by design), so
        // nothing would be left to find by then.
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "other-credential-types");
        p.setDefinition(new CpsFlowDefinition(
                String.join(
                        "\n",
                        "pipeline {",
                        "  agent any",
                        "  stages {",
                        "    stage('collect') {",
                        "      steps {",
                        "        withEphemeralCredentials([",
                        "          ephemeralSSHUserPrivateKey(id: 'SSH_KEY', description: 'ssh key'),",
                        "          ephemeralSecretFile(id: 'SECRET_FILE', description: 'secret file', fileName: 'creds.txt'),",
                        "          ephemeralCertificate(id: 'CERT', description: 'cert')",
                        "        ]) {",
                        "          withCredentials([sshUserPrivateKey(credentialsId: 'SSH_KEY', keyFileVariable: 'KEYFILE', usernameVariable: 'SSHUSER')]) {",
                        "            echo \"SSHUSER:${SSHUSER}\"",
                        "            echo \"SSHKEY:${readFile(KEYFILE)}\"",
                        "          }",
                        "          withCredentials([file(credentialsId: 'SECRET_FILE', variable: 'SECRETFILE')]) {",
                        "            echo \"FILECONTENT:${readFile(SECRETFILE)}\"",
                        "          }",
                        "          withCredentials([certificate(credentialsId: 'CERT', keystoreVariable: 'KEYSTORE', passwordVariable: 'CERTPW', aliasVariable: 'ALIAS')]) {",
                        "            echo 'CERT_BOUND_OK'",
                        "          }",
                        "        }",
                        "      }",
                        "    }",
                        "  }",
                        "}"),
                true));

        WorkflowRun run = p.scheduleBuild2(0).waitForStart();

        // withEphemeralCredentials resolves the declared specs one at a
        // time, so three missing IDs pause on input() three times in a row.
        String testKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----";
        waitForInput(run).proceed(Map.of("username", "deploy", "privateKey", testKey, "passphrase", ""));

        String fileContent = "hello ephemeral file";
        String fileBase64 = Base64.getEncoder().encodeToString(fileContent.getBytes(StandardCharsets.UTF_8));
        waitForInput(run).proceed(Map.of("contentBase64", fileBase64));

        // CertificateCredentialsImpl's constructor parses the keystore
        // eagerly, so this needs to be structurally real (if otherwise
        // empty) PKCS#12, not arbitrary bytes.
        String keystoreBase64 = Base64.getEncoder().encodeToString(emptyPkcs12Keystore("keystorepw"));
        waitForInput(run).proceed(Map.of("keystoreBase64", keystoreBase64, "password", "keystorepw"));

        j.assertBuildStatusSuccess(j.waitForCompletion(run));

        j.assertLogContains("SSHUSER:deploy", run);
        j.assertLogContains("SSHKEY:" + testKey, run);
        j.assertLogContains("FILECONTENT:" + fileContent, run);
        j.assertLogContains("CERT_BOUND_OK", run);
        j.assertLogNotContains("keystorepw", run);
    }

    /** Helper for tests: a structurally valid (if empty) PKCS#12 keystore, for
     * exercising EphemeralCertificate without needing a real certificate. */
    private static byte[] emptyPkcs12Keystore(String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password.toCharArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, password.toCharArray());
        return out.toByteArray();
    }

    /** Helper for tests: stall until a pending input() is reached in the monitored pipeline.
     * See {@link #missingCredentialPromptsAndReusesCacheOnSecondCall} about interacting
     * with that input() from the test code.
     */
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
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for the pipeline to pause on input()");
    }

    /** Helper for tests: assert that NO pending input() is reached in the monitored pipeline. */
    private static void assertNoPendingInput(WorkflowRun run) throws Exception {
        InputAction action = run.getAction(InputAction.class);
        boolean waiting = action != null && !action.getExecutions().isEmpty();
        assertTrue(!waiting, "expected no pending input(), but one is waiting");
    }
}
