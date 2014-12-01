package hudson.plugins.git.extensions;

import hudson.DescriptorExtensionList;
import hudson.model.Descriptor;
import hudson.plugins.git.GitSCM;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class GitSCMExtensionDescriptor extends Descriptor<GitSCMExtension> {
    private static final Logger LOGGER = Logger.getLogger(GitSCMExtensionDescriptor.class.getName());

    public boolean isApplicable(Class<? extends GitSCM> type) {
        return true;
    }

    public static DescriptorExtensionList<GitSCMExtension,GitSCMExtensionDescriptor> all() {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            LOGGER.severe("Jenkins instance is null in GitSCMExtensionDescriptor");
            return null;
        }
        return jenkins.getDescriptorList(GitSCMExtension.class);
    }
}
