package jenkins.plugins.git;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Revision;
import hudson.plugins.git.TestGitRepo;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionTest;
import hudson.plugins.git.util.BuildData;
import org.eclipse.jgit.lib.Constants;
import org.junit.Test;

import static org.junit.Assert.*;

public class MergeWithGitSCMExtensionTest extends GitSCMExtensionTest {

    private FreeStyleProject project;

    private TestGitRepo repo;
    private String baseName;
    private String baseHash;
    private String DEFAULT_BRANCH_FILE = "commitFileBase";

    @Override
    public void before() throws Exception {
        repo = new TestGitRepo("repo", tmp.newFolder(), listener);
        // make an initial commit to default branch and get hash
        this.baseHash = repo.commit(DEFAULT_BRANCH_FILE, repo.johnDoe, "Initial Commit");
        // set the base name as HEAD
        this.baseName = repo.getDefaultBranchName();
        assertNotEquals("", this.baseName);
        project = setupBasicProject(repo);
        // create integration branch
        repo.git.branch("integration");

    }
    @Test
    public void testBasicMergeWithSCMExtension() throws Exception {
        FreeStyleBuild baseBuild = build(project, Result.SUCCESS);
    }

    @Test
    public void testFailedMergeWithSCMExtension() throws Exception {
        FreeStyleBuild firstBuild = build(project, Result.SUCCESS);
        assertEquals(GitSCM.class, project.getScm().getClass());
        GitSCM gitSCM = (GitSCM)project.getScm();
        BuildData buildData = gitSCM.getBuildData(firstBuild);
        assertNotNull("Build data not found", buildData);
        assertEquals(firstBuild.getNumber(), buildData.lastBuild.getBuildNumber());
        Revision firstMarked = buildData.lastBuild.getMarked();
        Revision firstRevision = buildData.lastBuild.getRevision();
        assertNotNull(firstMarked);
        assertNotNull(firstRevision);

        // delete integration branch successfully and commit successfully
        repo.git.deleteBranch("integration");
        repo.git.checkoutBranch("integration", repo.getDefaultBranchName());
        this.baseName = Constants.HEAD;
        this.baseHash = repo.git.revParse(baseName).name();
        repo.commit(DEFAULT_BRANCH_FILE, "new content on integration branch", repo.johnDoe, repo.johnDoe, "Commit success!");
        repo.git.checkout().ref(repo.getDefaultBranchName()).execute();

        // as baseName and baseHash don't change in default branch, this commit should  merge !
        assertFalse("SCM polling should not detect any more changes after build", project.poll(listener).hasChanges());
        String conflictSha1= repo.commit(DEFAULT_BRANCH_FILE, "new John Doe content will conflict", repo.johnDoe, repo.johnDoe, "Commit success!");
        assertTrue("SCM polling should detect changes", project.poll(listener).hasChanges());


        FreeStyleBuild secondBuild = build(project, Result.SUCCESS);
        assertEquals(secondBuild.getNumber(), gitSCM.getBuildData(secondBuild).lastBuild.getBuildNumber());
        // buildData should mark this as built
        assertEquals(conflictSha1, gitSCM.getBuildData(secondBuild).lastBuild.getMarked().getSha1String());
        assertEquals(conflictSha1, gitSCM.getBuildData(secondBuild).lastBuild.getRevision().getSha1String());

        // Check to see that build data is not corrupted (JENKINS-44037)
        assertEquals(firstBuild.getNumber(), gitSCM.getBuildData(firstBuild).lastBuild.getBuildNumber());
        assertEquals(firstMarked, gitSCM.getBuildData(firstBuild).lastBuild.getMarked());
        assertEquals(firstRevision, gitSCM.getBuildData(firstBuild).lastBuild.getRevision());
    }

    @Override
    protected GitSCMExtension getExtension() {
        return new MergeWithGitSCMExtension(baseName,baseHash);
    }

}
