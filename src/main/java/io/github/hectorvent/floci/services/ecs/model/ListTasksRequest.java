package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A parsed {@code ListTasks} request. Every member narrows the listing. */
@RegisterForReflection
public class ListTasksRequest {

    private String cluster;
    private String desiredStatus;
    private String family;
    private String serviceName;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getDesiredStatus() { return desiredStatus; }
    public void setDesiredStatus(String desiredStatus) { this.desiredStatus = desiredStatus; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
}
