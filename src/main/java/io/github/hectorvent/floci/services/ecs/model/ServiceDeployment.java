package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class ServiceDeployment {

    private String serviceDeploymentArn;
    private String serviceArn;
    private String clusterArn;
    /**
     * Internal only. AWS's {@code ServiceDeployment} shape has no {@code taskDefinition} member:
     * a caller reaches the task definition through {@link #targetServiceRevisionArn}, so this is
     * never written to the wire.
     */
    private String taskDefinition;
    private String status;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    /**
     * When a deployment that ended without completing was stopped. A stopped deployment carries
     * both this and {@link #finishedAt}, on the assumption that AWS does the same; its pages
     * point both ways and none of this was measured. A deployment that is still running, or that
     * succeeded, carries neither this nor {@link #statusReason}.
     */
    private Instant stoppedAt;
    /** Why the deployment is in the status it is in. Set for a deployment that was stopped. */
    private String statusReason;
    private Instant updatedAt;
    private String targetServiceRevisionArn;
    private List<String> sourceServiceRevisionArns;

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public String getTargetServiceRevisionArn() { return targetServiceRevisionArn; }
    public void setTargetServiceRevisionArn(String targetServiceRevisionArn) {
        this.targetServiceRevisionArn = targetServiceRevisionArn;
    }

    public List<String> getSourceServiceRevisionArns() { return sourceServiceRevisionArns; }
    public void setSourceServiceRevisionArns(List<String> sourceServiceRevisionArns) {
        this.sourceServiceRevisionArns = sourceServiceRevisionArns;
    }

    public String getServiceDeploymentArn() { return serviceDeploymentArn; }
    public void setServiceDeploymentArn(String serviceDeploymentArn) { this.serviceDeploymentArn = serviceDeploymentArn; }

    public String getServiceArn() { return serviceArn; }
    public void setServiceArn(String serviceArn) { this.serviceArn = serviceArn; }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
