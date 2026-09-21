package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * A parsed {@code UpdateService} request.
 *
 * <p>Every member is nullable: UpdateService leaves anything the request omitted exactly as it
 * was, so {@code null} means "unchanged" rather than "clear it".
 */
@RegisterForReflection
public class UpdateServiceRequest {

    private String cluster;
    private String service;
    private String taskDefinition;
    private Integer desiredCount;
    private NetworkConfiguration networkConfiguration;
    private String availabilityZoneRebalancing;
    private boolean forceNewDeployment;
    private Map<String, Object> serviceConnectConfiguration;
    private List<CapacityProviderStrategyItem> capacityProviderStrategy;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public Integer getDesiredCount() { return desiredCount; }
    public void setDesiredCount(Integer desiredCount) { this.desiredCount = desiredCount; }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public String getAvailabilityZoneRebalancing() { return availabilityZoneRebalancing; }
    public void setAvailabilityZoneRebalancing(String availabilityZoneRebalancing) {
        this.availabilityZoneRebalancing = availabilityZoneRebalancing;
    }

    public boolean isForceNewDeployment() { return forceNewDeployment; }
    public void setForceNewDeployment(boolean forceNewDeployment) { this.forceNewDeployment = forceNewDeployment; }

    public Map<String, Object> getServiceConnectConfiguration() { return serviceConnectConfiguration; }
    public void setServiceConnectConfiguration(Map<String, Object> serviceConnectConfiguration) {
        this.serviceConnectConfiguration = serviceConnectConfiguration;
    }

    public List<CapacityProviderStrategyItem> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<CapacityProviderStrategyItem> capacityProviderStrategy) {
        this.capacityProviderStrategy = capacityProviderStrategy;
    }

}
