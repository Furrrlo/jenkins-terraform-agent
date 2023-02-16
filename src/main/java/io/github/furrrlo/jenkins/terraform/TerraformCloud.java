package io.github.furrrlo.jenkins.terraform;

import com.google.common.base.Strings;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("unused")
public class TerraformCloud extends Cloud {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerraformCloud.class);
    private static final Lock PROVISION_LOCK = new ReentrantLock();

    private final List<? extends TerraformAgentTemplate> templates;
    private final int timeoutMinutes;
    private final int agentTimeoutMinutes;

    @DataBoundConstructor
    public TerraformCloud(String name,
                          String timeoutMinutes,
                          String agentTimeoutMinutes,
                          List<? extends TerraformAgentTemplate> templates) {
        super(name);

        this.templates = templates == null ? Collections.emptyList() : templates;
        this.timeoutMinutes = timeoutMinutes == null || timeoutMinutes.isEmpty() ? 10 : Integer.parseInt(timeoutMinutes);
        this.agentTimeoutMinutes = agentTimeoutMinutes == null || agentTimeoutMinutes.isEmpty() ? 10 : Integer.parseInt(agentTimeoutMinutes);
    }

    @Override
    public boolean canProvision(CloudState state) {
        return templates.stream().anyMatch(t -> t.matches(state.getLabel()));
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        PROVISION_LOCK.lock();
        try {
            List<NodeProvisioner.PlannedNode> provisioningNodes = new ArrayList<>();
            try {
                while (excessWorkload > 0) {
                    final TerraformAgentTemplate template = templates.stream()
                            .filter(t -> t.matches(state.getLabel()) && !t.isInstanceCapReached(name))
                            .findFirst()
                            .orElse(null);
                    if (template == null)
                        break;

                    final String agentName = TerraformAgentName.generateAgentName(name, template.getName());

                    final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(name, template.getName(), agentName);
                    provisioningNodes.add(new TrackedPlannedNode(provisioningId, template.getNumExecutors(), Computer.threadPoolForRemoting.submit(() -> {
                        TerraformAgent agent;
                        PROVISION_LOCK.lock();
                        try {
                            agent = template.provision(this, provisioningId, agentName);
                            Jenkins.get().addNode(agent);
                        } finally {
                            PROVISION_LOCK.unlock();
                        }

                        agent.toComputer().connect(false).get();
                        return agent;
                    })));

                    excessWorkload -= template.getNumExecutors();
                }

                LOGGER.info("Provisioning {} nodes", provisioningNodes.size());

                return provisioningNodes;
            } catch (Exception e) {
                LOGGER.error("Failed to provision node", e);
                return Collections.emptyList();
            }
        } finally {
            PROVISION_LOCK.unlock();
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {

        public DescriptorImpl() {
            load();
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Terraform Cloud";
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (name == null || name.isEmpty())
                return FormValidation.error("Must be set");
            if (!TerraformAgentName.isValidCloudName(name))
                return FormValidation.error("Must consist of A-Z, a-z, 0-9 and . symbols");
            return FormValidation.ok();
        }

        public FormValidation doCheckAgentTimeoutMinutes(@QueryParameter String agentTimeoutMinutes) {
            if (agentTimeoutMinutes == null || agentTimeoutMinutes.isEmpty())
                return FormValidation.error("Agent timeout must be set");

            int instanceCapNumber;
            try {
                instanceCapNumber = Integer.parseInt(agentTimeoutMinutes);
            } catch (Exception e) {
                return FormValidation.error("Agent timeout must be a number");
            }

            if (instanceCapNumber < 0)
                return FormValidation.error("Agent timeout must be a positive number");
            return FormValidation.ok();
        }

        public FormValidation doCheckTimeoutMinutes(@QueryParameter String timeoutMinutes) {
            if (timeoutMinutes == null || timeoutMinutes.isEmpty())
                return FormValidation.error("Timeout must be set");

            int instanceCapNumber;
            try {
                instanceCapNumber = Integer.parseInt(timeoutMinutes);
            } catch (Exception e) {
                return FormValidation.error("Timeout must be a number");
            }

            if (instanceCapNumber < 0)
                return FormValidation.error("Timeout must be a positive number");
            return FormValidation.ok();
        }
    }

    public List<? extends TerraformAgentTemplate> getTemplates() {
        return templates;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public int getAgentTimeoutMinutes() {
        return agentTimeoutMinutes;
    }
}
