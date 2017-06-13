/*
 * The MIT License
 *
 * Copyright (c) 2013-2017, CloudBees, Inc., Stephen Connolly, Amadeus IT Group.
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
package jenkins.plugins.git;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Cache;
import hudson.plugins.git.GitTool;
import hudson.plugins.git.Revision;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.impl.BuildChooserSetting;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildChooser;
import hudson.plugins.git.util.BuildChooserContext;
import hudson.plugins.git.util.BuildChooserDescriptor;
import hudson.plugins.git.util.BuildData;
import hudson.remoting.VirtualChannel;
import hudson.scm.SCM;
import hudson.security.ACL;
import java.util.Map;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMProbe;
import jenkins.scm.api.SCMProbeStat;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceEvent;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.metadata.PrimaryInstanceMetadataAction;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jenkinsci.plugins.gitclient.FetchCommand;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.RepositoryCallback;

/**
 * @author Stephen Connolly
 */
public abstract class AbstractGitSCMSource extends SCMSource {

    private static final Logger LOGGER = Logger.getLogger(AbstractGitSCMSource.class.getName());

    public AbstractGitSCMSource(String id) {
        super(id);
    }

    @CheckForNull
    public abstract String getCredentialsId();

    /**
     * @return Git remote URL
     */
    public abstract String getRemote();

    public abstract String getIncludes();

    public abstract String getExcludes();

    /**
     * Gets {@link GitRepositoryBrowser} to be used with this SCMSource.
     * @return Repository browser or {@code null} if the default tool should be used.
     * @since 2.5.1
     */
    @CheckForNull
    public GitRepositoryBrowser getBrowser() {
        // Always return null by default
        return null;
    }

    /**
     * Gets Git tool to be used for this SCM Source.
     * @return Git Tool or {@code null} if the default tool should be used.
     * @since 2.5.1
     */
    @CheckForNull
    public String getGitTool() {
        // Always return null by default
        return null;
    }

    /**
     * Gets list of extensions, which should be used with this branch source.
     * @return List of Extensions to be used. May be empty
     * @since 2.5.1
     */
    @NonNull
    public List<GitSCMExtension> getExtensions() {
        // Always return empty list
        return Collections.emptyList();
    }

    public String getRemoteName() {
      return "origin";
    }

    @CheckForNull
    @SuppressFBWarnings(value="NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification="Jenkins instance never null")
    protected GitTool resolveGitTool() {
        GitTool tool = Jenkins.getInstance().getDescriptorByType(GitTool.DescriptorImpl.class)
            .getInstallation(getGitTool());
        if (getGitTool() == null) {
            tool = GitTool.getDefaultInstallation();
        }
        return tool;
    }

    private interface Retriever<T> {
        T run(GitClient client, String remoteName) throws IOException, InterruptedException;
    }

    @NonNull
    private <T> T doRetrieve(Retriever<T> retriever, @NonNull TaskListener listener, boolean prune)
            throws IOException, InterruptedException {
        Cache.lock(getRemote());
        try {
            File cacheDir = Cache.getCacheDir(getRemote());
            Git git = Git.with(listener, new EnvVars(EnvVars.masterEnvVars)).in(cacheDir);
            GitTool tool = resolveGitTool();
            if (tool != null) {
                git.using(tool.getGitExe());
            }
            GitClient client = git.getClient();
            client.addDefaultCredentials(getCredentials());
            if (!client.hasGitRepo()) {
                listener.getLogger().println("Creating git repository in " + cacheDir);
                client.init();
            }
            String remoteName = getRemoteName();
            listener.getLogger().println("Setting " + remoteName + " to " + getRemote());
            client.setRemoteUrl(remoteName, getRemote());
            listener.getLogger().println((prune ? "Fetching & pruning " : "Fetching ") + remoteName + "...");
            FetchCommand fetch = client.fetch_();
            if (prune) {
                fetch = fetch.prune();
            }
            URIish remoteURI = null;
            try {
                remoteURI = new URIish(remoteName);
            } catch (URISyntaxException ex) {
                listener.getLogger().println("URI syntax exception for '" + remoteName + "' " + ex);
            }
            fetch.from(remoteURI, getRefSpecs()).execute();
            return retriever.run(client, remoteName);
        } finally {
            Cache.unlock(getRemote());
        }
    }

    @CheckForNull
    @Override
    protected SCMRevision retrieve(@NonNull final SCMHead head, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        return doRetrieve(new Retriever<SCMRevision>() {
            @Override
            public SCMRevision run(GitClient client, String remoteName) throws IOException, InterruptedException {
                for (Branch b : client.getRemoteBranches()) {
                    String branchName = StringUtils.removeStart(b.getName(), remoteName + "/");
                    if (branchName.equals(head.getName())) {
                        return new SCMRevisionImpl(head, b.getSHA1String());
                    }
                }
                return null;
            }
        }, listener, /* we don't prune remotes here, as we just want one head's revision */false);
    }

    @Override
    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="Known non-serializable this")
    protected void retrieve(@CheckForNull final SCMSourceCriteria criteria,
                            @NonNull final SCMHeadObserver observer,
                            @CheckForNull final SCMHeadEvent<?> event,
                            @NonNull final TaskListener listener)
            throws IOException, InterruptedException {
        doRetrieve(new Retriever<Void>() {
            @Override
            public Void run(GitClient client, String remoteName) throws IOException, InterruptedException {
                final Repository repository = client.getRepository();
                listener.getLogger().println("Getting remote branches...");
                Set<SCMHead> includes = observer.getIncludes();
                try (RevWalk walk = new RevWalk(repository)) {
                    walk.setRetainBody(false);
                    for (Branch b : client.getRemoteBranches()) {
                        checkInterrupt();
                        if (!b.getName().startsWith(remoteName + "/")) {
                            continue;
                        }
                        final String branchName = StringUtils.removeStart(b.getName(), remoteName + "/");
                        SCMHead head = new SCMHead(branchName);
                        if (includes != null && !includes.contains(head)) {
                            continue;
                        }
                        listener.getLogger().println("Checking branch " + branchName);
                        if (isExcluded(branchName)){
                            continue;
                        }
                        if (criteria != null) {
                            RevCommit commit = walk.parseCommit(b.getSHA1());
                            final long lastModified = TimeUnit.SECONDS.toMillis(commit.getCommitTime());
                            final RevTree tree = commit.getTree();
                            SCMSourceCriteria.Probe probe = new SCMProbe() {
                                @Override
                                public void close() throws IOException {
                                    // no-op
                                }

                                @Override
                                public String name() {
                                    return branchName;
                                }

                                @Override
                                public long lastModified() {
                                    return lastModified;
                                }

                                @Override
                                @NonNull
                                @SuppressFBWarnings(value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
                                                    justification = "TreeWalk.forPath can return null, compiler "
                                                            + "generated code for try with resources handles it")
                                public SCMProbeStat stat(@NonNull String path) throws IOException {
                                    try (TreeWalk tw = TreeWalk.forPath(repository, path, tree)) {
                                        if (tw == null) {
                                            return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
                                        }
                                        FileMode fileMode = tw.getFileMode(0);
                                        if (fileMode == FileMode.MISSING) {
                                            return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
                                        }
                                        if (fileMode == FileMode.EXECUTABLE_FILE) {
                                            return SCMProbeStat.fromType(SCMFile.Type.REGULAR_FILE);
                                        }
                                        if (fileMode == FileMode.REGULAR_FILE) {
                                            return SCMProbeStat.fromType(SCMFile.Type.REGULAR_FILE);
                                        }
                                        if (fileMode == FileMode.SYMLINK) {
                                            return SCMProbeStat.fromType(SCMFile.Type.LINK);
                                        }
                                        if (fileMode == FileMode.TREE) {
                                            return SCMProbeStat.fromType(SCMFile.Type.DIRECTORY);
                                        }
                                        return SCMProbeStat.fromType(SCMFile.Type.OTHER);
                                    }
                                }
                            };
                            if (criteria.isHead(probe, listener)) {
                                listener.getLogger().println("Met criteria");
                            } else {
                                listener.getLogger().println("Does not meet criteria");
                                continue;
                            }
                        }
                        SCMRevision hash = new SCMRevisionImpl(head, b.getSHA1String());
                        observer.observe(head, hash);
                        if (!observer.isObserving()) {
                            return null;
                        }
                    }
                }

                listener.getLogger().println("Done.");
                return null;
            }
        }, listener, true);
    }

    @CheckForNull
    @Override
    protected SCMRevision retrieve(@NonNull final String revision, @NonNull final TaskListener listener) throws IOException, InterruptedException {
        return doRetrieve(new Retriever<SCMRevision>() {
            @Override
            public SCMRevision run(GitClient client, String remoteName) throws IOException, InterruptedException {
                String hash;
                try {
                    hash = client.revParse(revision).name();
                } catch (GitException x) {
                    // Try prepending origin/ in case it was a branch.
                    try {
                        hash = client.revParse("origin/" + revision).name();
                    } catch (GitException x2) {
                        listener.getLogger().println(x.getMessage());
                        listener.getLogger().println(x2.getMessage());
                        return null;
                    }
                }
                return new SCMRevisionImpl(new SCMHead(revision), hash);
            }
        }, listener, false);
    }

    @NonNull
    @Override
    protected Set<String> retrieveRevisions(@NonNull final TaskListener listener) throws IOException, InterruptedException {
        return doRetrieve(new Retriever<Set<String>>() {
            @Override
            public Set<String> run(GitClient client, String remoteName) throws IOException, InterruptedException {
                Set<String> revisions = new HashSet<String>();
                for (Branch branch : client.getRemoteBranches()) {
                    revisions.add(branch.getName().replaceFirst("^origin/", ""));
                }
                revisions.addAll(client.getTagNames("*"));
                return revisions;
            }
        }, listener, false);
    }

    @NonNull
    @Override
    protected List<Action> retrieveActions(@CheckForNull SCMSourceEvent event, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        return doRetrieve(new Retriever<List<Action>>() {
            @Override
            public List<Action> run(GitClient client, String remoteName) throws IOException, InterruptedException {
                Map<String, String> symrefs = client.getRemoteSymbolicReferences(getRemote(), null);
                if (symrefs.containsKey(Constants.HEAD)) {
                    // Hurrah! The Server is Git 1.8.5 or newer and our client has symref reporting
                    String target = symrefs.get(Constants.HEAD);
                    if (target.startsWith(Constants.R_HEADS)) {
                        // shorten standard names
                        target = target.substring(Constants.R_HEADS.length());
                    }
                    List<Action> result = new ArrayList<>();
                    if (StringUtils.isNotBlank(target)) {
                        result.add(new GitRemoteHeadRefAction(getRemote(), target));
                    }
                    return result;
                }
                // Ok, now we do it the old-school way... see what ref has the same hash as HEAD
                // I think we will still need to keep this code path even if JGit implements
                // https://bugs.eclipse.org/bugs/show_bug.cgi?id=514052 as there is always the potential that
                // the remote server is Git 1.8.4 or earlier, or that the local CLI git implementation is 
                // older than git 2.8.0 (CentOS 6, CentOS 7, Debian 7, Debian 8, Ubuntu 14, and Ubuntu 16)
                Map<String, ObjectId> remoteReferences = client.getRemoteReferences(getRemote(), null, false, false);
                if (remoteReferences.containsKey(Constants.HEAD)) {
                    ObjectId head = remoteReferences.get(Constants.HEAD);
                    Set<String> names = new TreeSet<>();
                    for (Map.Entry<String, ObjectId> entry: remoteReferences.entrySet()) {
                        if (entry.getKey().equals(Constants.HEAD)) continue;
                        if (head.equals(entry.getValue())) {
                            names.add(entry.getKey());
                        }
                    }
                    // if there is one and only one match, that's the winner
                    if (names.size() == 1) {
                        String target = names.iterator().next();
                        if (target.startsWith(Constants.R_HEADS)) {
                            // shorten standard names
                            target = target.substring(Constants.R_HEADS.length());
                        }
                        List<Action> result = new ArrayList<>();
                        if (StringUtils.isNotBlank(target)) {
                            result.add(new GitRemoteHeadRefAction(getRemote(), target));
                        }
                        return result;
                    }
                    // if there are multiple matches, prefer `master`
                    if (names.contains(Constants.R_HEADS + Constants.MASTER)) {
                        List<Action> result = new ArrayList<Action>();
                        result.add(new GitRemoteHeadRefAction(getRemote(), Constants.MASTER));
                        return result;
                    }
                }
                // Give up, there's no way to get the primary branch
                return new ArrayList<>();
            }
        }, listener, false);
    }

    @NonNull
    @Override
    protected List<Action> retrieveActions(@NonNull SCMHead head, @CheckForNull SCMHeadEvent event,
                                           @NonNull TaskListener listener) throws IOException, InterruptedException {
        SCMSourceOwner owner = getOwner();
        if (owner instanceof Actionable) {
            for (GitRemoteHeadRefAction a: ((Actionable) owner).getActions(GitRemoteHeadRefAction.class)) {
                if (getRemote().equals(a.getRemote())) {
                    if (head.getName().equals(a.getName())) {
                        return Collections.<Action>singletonList(new PrimaryInstanceMetadataAction());
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    @CheckForNull
    protected StandardUsernameCredentials getCredentials() {
        String credentialsId = getCredentialsId();
        if (credentialsId == null) {
            return null;
        }
        return CredentialsMatchers
                .firstOrNull(
                        CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, getOwner(),
                                ACL.SYSTEM, URIRequirementBuilder.fromUri(getRemote()).build()),
                        CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId),
                                GitClient.CREDENTIALS_MATCHER));
    }

    protected abstract List<RefSpec> getRefSpecs();

    @NonNull
    @Override
    public SCM build(@NonNull SCMHead head, @CheckForNull SCMRevision revision) {
        List<GitSCMExtension> extensions = new ArrayList<GitSCMExtension>(getExtensions());
        if (revision instanceof SCMRevisionImpl) {
            extensions.add(new BuildChooserSetting(new SpecificRevisionBuildChooser((SCMRevisionImpl) revision)));
        }
        return new GitSCM(
                getRemoteConfigs(),
                Collections.singletonList(new BranchSpec(head.getName())),
                false, Collections.<SubmoduleConfig>emptyList(),
                getBrowser(), getGitTool(),
                extensions);
    }

    protected List<UserRemoteConfig> getRemoteConfigs() {
        List<RefSpec> refSpecs = getRefSpecs();
        List<UserRemoteConfig> result = new ArrayList<>(refSpecs.size());
        String remote = getRemote();
        for (RefSpec refSpec : refSpecs) {
            result.add(new UserRemoteConfig(remote, getRemoteName(), refSpec.toString(), getCredentialsId()));
        }
        return result;
    }
    
    /**
     * Returns true if the branchName isn't matched by includes or is matched by excludes.
     * 
     * @param branchName name of branch to be tested
     * @return true if branchName is excluded or is not included
     */
    protected boolean isExcluded (String branchName){
      return !Pattern.matches(getPattern(getIncludes()), branchName) || (Pattern.matches(getPattern(getExcludes()), branchName));
    }
    
    /**
     * Returns the pattern corresponding to the branches containing wildcards. 
     * 
     * @param branches branch names to evaluate
     * @return pattern corresponding to the branches containing wildcards
     */
    private String getPattern(String branches){
      StringBuilder quotedBranches = new StringBuilder();
      for (String wildcard : branches.split(" ")){
        StringBuilder quotedBranch = new StringBuilder();
        for(String branch : wildcard.split("(?=[*])|(?<=[*])")){
          if (branch.equals("*")) {
            quotedBranch.append(".*");
          } else if (!branch.isEmpty()) {
            quotedBranch.append(Pattern.quote(branch));
          }
        }
        if (quotedBranches.length()>0) {
          quotedBranches.append("|");
        }
        quotedBranches.append(quotedBranch);
      }
      return quotedBranches.toString();
    }

    /**
     * Our implementation.
     */
    public static class SCMRevisionImpl extends SCMRevision {

        /**
         * The subversion revision.
         */
        private String hash;

        public SCMRevisionImpl(SCMHead head, String hash) {
            super(head);
            this.hash = hash;
        }

        public String getHash() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            SCMRevisionImpl that = (SCMRevisionImpl) o;

            return StringUtils.equals(hash, that.hash) && getHead().equals(that.getHead());

        }

        @Override
        public int hashCode() {
            return hash != null ? hash.hashCode() : 0;
        }

        @Override
        public String toString() {
            return hash;
        }

    }

    public static class SpecificRevisionBuildChooser extends BuildChooser {

        private final Revision revision;

        public SpecificRevisionBuildChooser(SCMRevisionImpl revision) {
            ObjectId sha1 = ObjectId.fromString(revision.getHash());
            String name = revision.getHead().getName();
            this.revision = new Revision(sha1, Collections.singleton(new Branch(name, sha1)));
        }

        @Override
        public Collection<Revision> getCandidateRevisions(boolean isPollCall, String singleBranch, GitClient git,
                                                          TaskListener listener, BuildData buildData,
                                                          BuildChooserContext context)
                throws GitException, IOException, InterruptedException {
            return Collections.singleton(revision);
        }

        @Override
        public Build prevBuildForChangelog(String branch, @Nullable BuildData data, GitClient git,
                                           BuildChooserContext context) throws IOException, InterruptedException {
            // we have ditched that crazy multiple branch stuff from the regular GIT SCM.
            return data == null ? null : data.lastBuild;
        }

        @Extension
        public static class DescriptorImpl extends BuildChooserDescriptor {

            @Override
            public String getDisplayName() {
                return "Specific revision";
            }

            public boolean isApplicable(java.lang.Class<? extends Item> job) {
                return SCMSourceOwner.class.isAssignableFrom(job);
            }

        }

    }
}
