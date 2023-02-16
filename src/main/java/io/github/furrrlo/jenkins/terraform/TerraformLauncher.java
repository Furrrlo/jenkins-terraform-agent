package io.github.furrrlo.jenkins.terraform;

import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import org.kohsuke.stapler.DataBoundConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class TerraformLauncher extends JNLPLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerraformLauncher.class);
    // Report progress every 30 seconds
    private static final long REPORT_INTERVAL = TimeUnit.SECONDS.toMillis(30L);

    private boolean launched;

    @DataBoundConstructor
    public TerraformLauncher(String tunnel) {
        super(tunnel);
    }

    public TerraformLauncher(boolean enableWorkDir) {
        super(enableWorkDir);
    }

    @Override
    public boolean isLaunchSupported() {
        return !launched;
    }

    @Override
    public synchronized void launch(SlaveComputer computer, TaskListener listener) {
        if (!(computer instanceof TerraformComputer))
            throw new IllegalArgumentException("This Launcher can be used only with TerraformComputer");

        final TerraformComputer kubernetesComputer = (TerraformComputer) computer;
        computer.setAcceptingTasks(false);

        final TerraformAgent node = kubernetesComputer.getNode();
        if (node == null)
            throw new IllegalStateException("Node has been removed, cannot launch " + computer.getName());

        if (launched) {
            LOGGER.info("Agent has already been launched, activating: {}", node.getNodeName());
            computer.setAcceptingTasks(true);
            return;
        }

        try {
            // The terraform command to create the node was already run, we just need to wait for the agent to connect
            long waitForAgentToConnect = TimeUnit.MINUTES.toSeconds(node.getCloud().getAgentTimeoutMinutes());
            long waitedForAgent;

            SlaveComputer slaveComputer = null;
            long lastReportTimestamp = System.currentTimeMillis();
            for (waitedForAgent = 0; waitedForAgent < waitForAgentToConnect; waitedForAgent++) {
                slaveComputer = node.getComputer();

                if (slaveComputer == null)
                    throw new IllegalStateException("Node was deleted, computer is null");

                if (slaveComputer.isOnline())
                    break;

                if (lastReportTimestamp + REPORT_INTERVAL < System.currentTimeMillis()) {
                    LOGGER.info("Waiting for agent to connect ({}/{}): {}",
                            waitedForAgent, waitForAgentToConnect, node.getId());
                    listener.getLogger().printf("Waiting for agent to connect (%s/%s): %s%n",
                            waitedForAgent, waitForAgentToConnect, node.getId());
                    lastReportTimestamp = System.currentTimeMillis();
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new IllegalStateException("Interrupted while waiting for the agent to connect", e);
                }
            }

            if (slaveComputer == null || slaveComputer.isOffline())
                throw new IllegalStateException("Agent did not connect after " + waitedForAgent + " seconds");

            computer.setAcceptingTasks(true);
            launched = true;
            try {
                // We need to persist the "launched" setting...
                node.save();
            } catch (IOException e) {
                LOGGER.warn("Could not save() agent", e);
            }
        } catch (Throwable ex) {
            LOGGER.error("Error in provisioning; agent={}, template={}", node, node.getTemplate());

            try {
                node.terminate();
            } catch (IOException | InterruptedException e) {
                LOGGER.warn("Unable to remove Jenkins node", e);
            }

            throw new RuntimeException(ex);
        }
    }
}
