package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A parsed {@code CreateTaskSet} request. */
@RegisterForReflection
public class CreateTaskSetRequest {

    private String cluster;
    private String service;
    private String taskDefinition;
    private LaunchType launchType;
    private Double scaleValue;
    private String scaleUnit;
    private String externalId;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public Double getScaleValue() { return scaleValue; }
    public void setScaleValue(Double scaleValue) { this.scaleValue = scaleValue; }

    public String getScaleUnit() { return scaleUnit; }
    public void setScaleUnit(String scaleUnit) { this.scaleUnit = scaleUnit; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
}
