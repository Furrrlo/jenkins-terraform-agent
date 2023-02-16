package io.github.furrrlo.jenkins.terraform;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;

import javax.annotation.Nullable;
import java.io.IOException;

public class TerraformAgent extends AbstractCloudSlave implements TrackedItem {

    private final ProvisioningActivity.Id provisioningId;
    private final TerraformCloud cloud;
    private final TerraformAgentTemplate template;
    private final LocalTerraformInstallation.WorkDir workDir;

    protected TerraformAgent(ProvisioningActivity.Id provisioningId,
                             @NonNull String name,
                             TerraformCloud cloud,
                             TerraformAgentTemplate template,
                             LocalTerraformInstallation.WorkDir workDir) throws Descriptor.FormException, IOException {
        super(name, template.getWorkspacePath(), new TerraformLauncher(false));
        this.provisioningId = provisioningId;
        this.cloud = cloud;
        this.template = template;
        this.workDir = workDir;
        setRetentionStrategy(template.getNumExecutors() == 1 && template.getIdleTerminationInMinutes() == 0 ?
                new OnceRetentionStrategy(5) :
                new CloudRetentionStrategy(template.getIdleTerminationInMinutes()));
    }

    @Override
    public AbstractCloudComputer<?> createComputer() {
        return new TerraformComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException {
        try(LocalTerraformInstallation.WorkDir workDir = this.workDir) {
            TerraformAgentTemplate.executeDestroy(cloud, workDir, name);
        }
    }

    @Override
    public @Nullable ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    public TerraformCloud getCloud() {
        return cloud;
    }

    public TerraformAgentTemplate getTemplate() {
        return template;
    }
}
