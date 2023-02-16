package io.github.furrrlo.jenkins.terraform;

import hudson.slaves.AbstractCloudComputer;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import javax.annotation.Nullable;

public class TerraformComputer extends AbstractCloudComputer<TerraformAgent> implements TrackedItem {

    private final ProvisioningActivity.Id provisioningId;

    public TerraformComputer(TerraformAgent slave) {
        super(slave);
        provisioningId = slave.getId();
    }

    @Nullable
    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }
}
