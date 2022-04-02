package hudson.plugins.git.browser;

import hudson.model.TaskListener;
import java.io.File;
import jenkins.plugins.git.CliGitCommand;
import jenkins.plugins.git.GitSampleRepoRule;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class GitLabWorkflowTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();
    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    @Test
    public void checkoutWithVersion() throws Exception {
        sampleRepo.init();
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                + "  checkout(\n"
                + "    [$class: 'GitSCM',\n"
                + "     browser: [$class: 'GitLab', repoUrl: 'https://a.org/a/b', version: '9.0'],\n"
                + "     branches: [[name: '" + sampleRepo.getDefaultBranchName() + "']],\n"
                + "     userRemoteConfigs: [[url: $/" + sampleRepo + "/$]]]\n"
                + "  )\n"
                + "  def tokenBranch = tm '${GIT_BRANCH,fullName=false}'\n"
                + "  echo \"token macro expanded branch is ${tokenBranch}\"\n"
                + "}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.waitForMessage("token macro expanded branch is " + sampleRepo.getDefaultBranchName(), b);
    }

    @Test
    public void checkoutWithoutVersion() throws Exception {
        sampleRepo.init();
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  checkout(\n"
                        + "    [$class: 'GitSCM',\n"
                        + "     browser: [$class: 'GitLab', repoUrl: 'https://a.org/a/b'],\n"
                        + "     branches: [[name: '" + sampleRepo.getDefaultBranchName() + "']],\n"
                        + "    userRemoteConfigs: [[url: $/" + sampleRepo + "/$]]]\n"
                        + "  )\n"
                        + "  def tokenBranch = tm '${GIT_BRANCH,fullName=false}'\n"
                        + "  echo \"token macro expanded branch is ${tokenBranch}\"\n"
                        + "}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.waitForMessage("token macro expanded branch is " + sampleRepo.getDefaultBranchName(), b);
    }
}
