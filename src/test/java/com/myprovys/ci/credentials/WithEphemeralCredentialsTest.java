package com.myprovys.ci.credentials;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Illustrates and confirms the withEphemeralCredentials contract end to end,
 * against a real embedded Jenkins running declarative pipelines:
 * <ul>
 *   <li>an ID already resolvable via a real credentials store is left alone
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
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "EXISTING_CRED", "pre-registered", "alice", "s3cr3t"));
        SystemCredentialsProvider.getInstance().save();

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "already-registered");
        p.setDefinition(new CpsFlowDefinition(String.join("\n",
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
                "}"
        ), true));

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
        p.setDefinition(new CpsFlowDefinition(String.join("\n",
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
                "  }",
                "}"
        ), true));

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
        j.assertLogNotContains("hunter2", run);
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
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for the pipeline to pause on input()");
    }

    private static void assertNoPendingInput(WorkflowRun run) throws Exception {
        InputAction action = run.getAction(InputAction.class);
        boolean waiting = action != null && !action.getExecutions().isEmpty();
        assertTrue(!waiting, "expected no pending input(), but one is waiting");
    }
}
