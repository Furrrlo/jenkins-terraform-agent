package io.github.furrrlo.jenkins.terraform;

import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.common.base.Strings;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpAgentReceiver;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.terraform.Configuration;
import org.jenkinsci.plugins.terraform.TerraformBuildWrapper;
import org.jenkinsci.plugins.terraform.TerraformInstallation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class TerraformAgentTemplate extends AbstractDescribableImpl<TerraformAgentTemplate> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerraformAgentTemplate.class);

    private final String name;
    private final String labelString;
    private final String labels;
    private final boolean labellessJobsAllowed;
    private final List<? extends TerraformCredentials> credentials;
    private final Configuration terraformConfig;
    private final String terraformInstallation;
    private final boolean useWebsocket;
    private final String workspacePath;
    private final int idleTerminationInMinutes;
    private final int numExecutors;
    private final int instanceCap;

    private transient Set<LabelAtom> labelSet;

    @DataBoundConstructor
    public TerraformAgentTemplate(String name,
                                  String labelString,
                                  boolean labellessJobsAllowed,
                                  List<? extends TerraformCredentials> credentials,
                                  Configuration terraformConfig,
                                  String terraformInstallation,
                                  boolean useWebsocket,
                                  String workspacePath,
                                  String idleTerminationInMinutes,
                                  String numExecutors,
                                  String instanceCap) {

        this.name = name;
        this.labelString = labelString;
        this.labellessJobsAllowed = labellessJobsAllowed;
        this.labels = Util.fixNull(labelString);
        this.terraformConfig = terraformConfig;
        this.terraformInstallation = terraformInstallation;
        this.credentials = credentials == null ? Collections.emptyList() : credentials;
        this.useWebsocket = useWebsocket;
        this.workspacePath = workspacePath;
        this.idleTerminationInMinutes = tryParseInteger(idleTerminationInMinutes, () -> {
            LOGGER.info("Invalid integer {} for idleTerminationInMinutes, defaulting to 10", idleTerminationInMinutes);
            return 10;
        });
        this.numExecutors = tryParseInteger(numExecutors, () -> {
            LOGGER.info("Invalid integer {} for numExecutors, defaulting to 1", numExecutors);
            return 1;
        });
        this.instanceCap = Integer.parseInt(instanceCap);

        readResolve();
    }

    @SuppressWarnings("UnusedReturnValue")
    protected Object readResolve() {
        labelSet = Label.parse(labels);
        return this;
    }

    public boolean matches(Label label) {
        return (label == null && labelSet.isEmpty()) ||
                (label == null && labellessJobsAllowed) ||
                (label != null && label.matches(labelSet));
    }

    public boolean isInstanceCapReached(String cloudName) {
        if (instanceCap == 0)
            return false;

        long count = Jenkins.get().getNodes().stream()
                .filter(n -> TerraformAgentName.isNodeInstanceOfTemplate(n.getDisplayName(), cloudName, name))
                .count();
        return count >= instanceCap;
    }

    public TerraformAgent provision(TerraformCloud cloud, ProvisioningActivity.Id provisioningId, String agentName) throws Exception {

        LOGGER.info("Provisioning agent with Terraform template {}...", name);

        final LocalTerraformInstallation installation = new LocalTerraformInstallation(
                Arrays.stream(((DescriptorImpl) getDescriptor()).getInstallations())
                        .filter(i -> terraformInstallation != null && i.getName().equals(terraformInstallation))
                        .findFirst()
                        .orElseThrow(() -> new Exception("Couldn't find Terraform installation " + terraformInstallation)));

        final Map<String, String> vars = new HashMap<>();
        vars.put("jenkins_url", Jenkins.get().getRootUrl());
        vars.put("jenkins_websocket", String.valueOf(useWebsocket));
        vars.put("jenkins_agent_name", agentName);
        vars.put("jenkins_agent_secret", JnlpAgentReceiver.DATABASE.getSecretOf(agentName));
        vars.put("jenkins_agent_workdir", workspacePath);

        final Map<String, String> credentialToVariable = credentials.stream().collect(Collectors.toMap(
                TerraformCredentials::getCredentialsId,
                TerraformCredentials::getVariable));
        final List<IdCredentials> allCredentials = TerraformCredentials.getCredentials(null, credentialToVariable.keySet());
        if(allCredentials.size() < credentialToVariable.keySet().size())
            throw new RuntimeException("Couldn't find all credentials: " +
                    "expected " + credentialToVariable.keySet() + ", found " + allCredentials);

        allCredentials.forEach(c -> {
            final String variableName = credentialToVariable.get(c.getId());
            if(c instanceof StandardUsernamePasswordCredentials) {
                final StandardUsernamePasswordCredentials credentials = (StandardUsernamePasswordCredentials) c;
                vars.put(variableName + "_usr", credentials.getUsername());
                vars.put(variableName + "_pwd", credentials.getPassword().getPlainText());
                return;
            }

            if(c instanceof StringCredentials) {
                final StringCredentials credentials = (StringCredentials) c;
                vars.put(variableName, credentials.getSecret().getPlainText());
                return;
            }

            throw new UnsupportedOperationException("Unsupported credential type " + c.getClass());
        });

        LocalTerraformInstallation.WorkDir workDir = installation.setupWorkDir(Jenkins.get().getRootDir(), agentName, terraformConfig, vars);
        try {
            executeInit(cloud, workDir, agentName);
            executeGet(cloud, workDir, agentName);

            try {
                executeApply(cloud, workDir, agentName);
            } catch (Throwable t) {
                try {
                    executeDestroy(cloud, workDir, agentName);
                } catch (Throwable ex) {
                    t.addSuppressed(ex);
                }

                throw t;
            }

            LOGGER.info("Creating new agent...");
            return new TerraformAgent(provisioningId, agentName, cloud, this, workDir);
        } catch (Throwable t) {
            workDir.close();
            throw t;
        }
    }

    private void executeInit(TerraformCloud cloud,
                             LocalTerraformInstallation.WorkDir workDir,
                             String agentName) throws Exception {
        try {
            workDir.runTerraformCmd(
                    pb -> {
                        pb.command().add("init");
                        pb.command().add("-no-color");
                        pb.command().add("-input=false");
                        return pb;
                    }, (process, output) -> {
                        final int exitCode = process.waitFor();
                        if (exitCode != 0)
                            throw new Exception("Terraform init exited with error code " + exitCode);
                        return exitCode;
                    });
        } catch (Throwable t) {
            throw new Exception("Terraform init failed", t);
        }
    }

    private void executeGet(TerraformCloud cloud,
                            LocalTerraformInstallation.WorkDir workDir,
                            String agentName) throws Exception {
        try {
            workDir.runTerraformCmd(pb -> {
                pb.command().add("get");
                pb.command().add("-no-color");
                return pb;
            }, (process, output) -> {
                final int exitCode = process.waitFor();
                if (exitCode != 0)
                    throw new Exception("Terraform get exited with error code " + exitCode);
                return exitCode;
            });
        } catch (Throwable t) {
            throw new Exception("Terraform get failed", t);
        }
    }

    public void executeApply(TerraformCloud cloud,
                             LocalTerraformInstallation.WorkDir workDir,
                             String agentName) throws Exception {
        try(Closeable ignored = workDir.writeFileVariable()) {
            workDir.runTerraformCmd(
                    pb -> {
                        pb.command().add("apply");
                        pb.command().add("-no-color");
                        pb.command().add("-input=false");
                        pb.command().add("-auto-approve");
                        pb.command().add("-state=" + workDir.getStateFile().getAbsolutePath());
                        pb.command().add("-var-file=" + workDir.getVariablesFile().getAbsolutePath());
                        return pb;
                    },
                    (process, output) -> {
                        if(!process.waitFor(cloud.getTimeoutMinutes(), TimeUnit.MINUTES)) {
                            process.destroy();
                            throw new Exception("Terraform apply timeout expired");
                        }

                        final int exitCode = process.exitValue();
                        if (exitCode != 0)
                            throw new Exception("Terraform apply exited with error code " + exitCode);
                        return exitCode;
                    });
        } catch (Throwable t) {
            throw new Exception("Terraform apply failed", t);
        }
    }

    public static void executeDestroy(TerraformCloud cloud,
                                      LocalTerraformInstallation.WorkDir workDir,
                                      String agentName) throws IOException {
        try(Closeable ignored = workDir.writeFileVariable()) {
            workDir.runTerraformCmd(
                    pb -> {
                        pb.command().add("apply");
                        pb.command().add("-no-color");
                        pb.command().add("-destroy");
                        pb.command().add("-input=false");
                        pb.command().add("-auto-approve");
                        pb.command().add("-state=" + workDir.getStateFile().getAbsolutePath());
                        pb.command().add("-var-file=" + workDir.getVariablesFile().getAbsolutePath());
                        return pb;
                    },
                    (process, output) -> {
                        final int exitCode = process.waitFor();
                        if (exitCode != 0)
                            throw new Exception("Terraform destroy exited with error code " + exitCode);
                        return exitCode;
                    });
        } catch (Throwable ex) {
            throw new IOException("Failed to terminate Terraform node", ex);
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<TerraformAgentTemplate> {

        public DescriptorImpl() {
            load();
        }

        @Override
        public @Nonnull String getDisplayName() {
            return "Terraform Agent Template";
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (Strings.isNullOrEmpty(name)) {
                return FormValidation.error("Must be set");
            } else {
                return FormValidation.ok();
            }
        }

        public TerraformInstallation[] getInstallations() {
            return Jenkins.get()
                    .getDescriptorByType(TerraformBuildWrapper.DescriptorImpl.class)
                    .getInstallations();
        }

        public ListBoxModel doFillTerraformInstallationItems() {
            ListBoxModel m = new ListBoxModel();
            for (TerraformInstallation inst : getInstallations())
                m.add(inst.getName());
            return m;
        }

        public boolean isInlineConfigChecked(TerraformAgentTemplate instance) {
            if (instance != null && instance.getTerraformConfig() != null)
                return (instance.getTerraformConfig().getInlineConfig() != null);
            return true;
        }

        public boolean isFileConfigChecked(TerraformAgentTemplate instance) {
            if (instance != null && instance.getTerraformConfig() != null)
                return (instance.getTerraformConfig().getFileConfig() != null);
            return false;
        }

        public FormValidation doCheckWorkspacePath(@QueryParameter String workspacePath) {
            return workspacePath == null || workspacePath.isEmpty() ?
                    FormValidation.error("Must be set") :
                    FormValidation.ok();
        }

        public FormValidation doCheckNumExecutors(@QueryParameter String numExecutors) {
            if (numExecutors == null || numExecutors.isEmpty())
                return FormValidation.error("Must be set");

            final int number;
            try {
                number = Integer.parseInt(numExecutors);
            } catch (Exception e) {
                return FormValidation.error("Must be a number");
            }

            if (number <= 0)
                return FormValidation.error("Must be a positive number");
            return FormValidation.ok();
        }

        public FormValidation doCheckIdleTerminationInMinutes(@QueryParameter String idleTerminationInMinutes) {
            if (idleTerminationInMinutes == null || idleTerminationInMinutes.isEmpty())
                return FormValidation.error("Must be set");

            try {
                Integer.parseInt(idleTerminationInMinutes);
            } catch (Exception e) {
                return FormValidation.error("Must be a number");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String instanceCap) {
            if (instanceCap == null || instanceCap.isEmpty())
                return FormValidation.error("Must be set");

            int number;
            try {
                number = Integer.parseInt(instanceCap);
            } catch (Exception e) {
                return FormValidation.error("Must be a number");
            }

            if (number < 0)
                return FormValidation.error("Must be a non-negative number");
            return FormValidation.ok();
        }
    }

    public String getName() {
        return name;
    }

    public String getLabelString() {
        return labelString;
    }

    public String getLabels() {
        return labels;
    }

    public boolean isLabellessJobsAllowed() {
        return labellessJobsAllowed;
    }

    public Configuration getTerraformConfig() {
        return terraformConfig;
    }

    public String getFileConfig() {
        return terraformConfig != null ? terraformConfig.getFileConfig() : null;
    }

    public String getInlineConfig() {
        return terraformConfig != null ? terraformConfig.getInlineConfig() : null;
    }

    public String getTerraformInstallation() {
        return terraformInstallation;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }

    public boolean isUseWebsocket() {
        return useWebsocket;
    }

    public List<? extends TerraformCredentials> getCredentials() {
        return credentials;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    private static int tryParseInteger(String integerString, IntSupplier defaultValue) {
        try {
            return Integer.parseInt(integerString);
        } catch (NumberFormatException e) {
            return defaultValue.getAsInt();
        }
    }
}
