package hudson.plugins.git.extensions;

import hudson.model.*;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.TestGitRepo;
import hudson.util.StreamTaskListener;
import org.eclipse.jgit.util.SystemReader;
import org.junit.After;
import java.io.File;
import jenkins.plugins.git.CliGitCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.CaptureEnvironmentBuilder;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;
import java.util.List;

/**
 * @author Kanstantsin Shautsou
 */
public abstract class GitSCMExtensionTest {

	protected TaskListener listener;

	@ClassRule
	public static BuildWatcher buildWatcher = new BuildWatcher();

	@Rule
	public JenkinsRule j = new JenkinsRule();

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	public static String defaultBranchName = "mast" + "er"; // Intentionally split string

	/**
	 * Determine the global default branch name.
	 * Command line git is moving towards more inclusive naming.
	 * Git 2.32.0 honors the configuration variable `init.defaultBranch` and uses it for the name of the initial branch.
	 * This method reads the global configuration and uses it to set the value of `defaultBranchName`.
	 */
	@BeforeClass
	public static void computeDefaultBranchName() throws Exception {
		File configDir = java.nio.file.Files.createTempDirectory("readGitConfig").toFile();
		CliGitCommand getDefaultBranchNameCmd = new CliGitCommand(Git.with(TaskListener.NULL, new hudson.EnvVars()).in(configDir).using("git").getClient());
		String[] output = getDefaultBranchNameCmd.runWithoutAssert("config", "--get", "init.defaultBranch");
		for (String s : output) {
			String result = s.trim();
			if (result != null && !result.isEmpty()) {
				defaultBranchName = result;
			}
		}
		Assert.assertTrue("Failed to delete temporary readGitConfig directory", configDir.delete());
	}

	@Before
	public void setUp() throws Exception {
		SystemReader.getInstance().getUserConfig().clear();
		listener = StreamTaskListener.fromStderr();
		before();
	}

	@Before
	public void allowNonRemoteCheckout() {
		GitSCM.ALLOW_LOCAL_CHECKOUT = true;
	}

	@After
	public void disallowNonRemoteCheckout() {
		GitSCM.ALLOW_LOCAL_CHECKOUT = false;
	}

	protected abstract void before() throws Exception;

	/**
	 * The {@link GitSCMExtension} being tested - this will be added to the
	 * project built in {@link #setupBasicProject(TestGitRepo)}
	 * @return the extension
	 */
	protected abstract GitSCMExtension getExtension();

	protected FreeStyleBuild build(final FreeStyleProject project, final Result expectedResult) throws Exception {
		final FreeStyleBuild build = project.scheduleBuild2(0, new Cause.UserIdCause()).get();
		if(expectedResult != null) {
			j.assertBuildStatus(expectedResult, build);
		}
		return build;
	}

	/**
	 * Create a {@link FreeStyleProject} configured with a {@link GitSCM}
	 * building on the default branch of the provided {@code repo},
	 * and with the extension described in {@link #getExtension()} added.
	 * @param repo git repository
	 * @return the created project
	 * @throws Exception on error
	 */
	protected FreeStyleProject setupBasicProject(TestGitRepo repo) throws Exception {
		GitSCMExtension extension = getExtension();
		FreeStyleProject project = j.createFreeStyleProject("p");
		List<BranchSpec> branches = Collections.singletonList(new BranchSpec(defaultBranchName));
		GitSCM scm = new GitSCM(
				repo.remoteConfigs(),
				branches,
				null, null,
				Collections.emptyList());
		scm.getExtensions().add(extension);
		project.setScm(scm);
		project.getBuildersList().add(new CaptureEnvironmentBuilder());
		return project;
	}
}
