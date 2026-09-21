package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** A parsed {@code CreateService} request. */
@RegisterForReflection
public class CreateServiceRequest {

    private String cluster;
    private String serviceName;
    private String taskDefinition;
    private int desiredCount = 1;
    private LaunchType launchType;
    private List<CapacityProviderStrategyItem> capacityProviderStrategy;
    private List<EcsLoadBalancer> loadBalancers;
    private NetworkConfiguration networkConfiguration;
    private Map<String, String> tags;
    private String schedulingStrategy;
    private String deploymentControllerType;
    private String availabilityZoneRebalancing;
    private Map<String, Object> serviceConnectConfiguration;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public int getDesiredCount() { return desiredCount; }
    public void setDesiredCount(int desiredCount) { this.desiredCount = desiredCount; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public List<CapacityProviderStrategyItem> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<CapacityProviderStrategyItem> capacityProviderStrategy) {
        this.capacityProviderStrategy = capacityProviderStrategy;
    }

    public List<EcsLoadBalancer> getLoadBalancers() { return loadBalancers; }
    public void setLoadBalancers(List<EcsLoadBalancer> loadBalancers) { this.loadBalancers = loadBalancers; }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getSchedulingStrategy() { return schedulingStrategy; }
    public void setSchedulingStrategy(String schedulingStrategy) { this.schedulingStrategy = schedulingStrategy; }

    public String getDeploymentControllerType() { return deploymentControllerType; }
    public void setDeploymentControllerType(String deploymentControllerType) {
        this.deploymentControllerType = deploymentControllerType;
    }

    public String getAvailabilityZoneRebalancing() { return availabilityZoneRebalancing; }
    public void setAvailabilityZoneRebalancing(String availabilityZoneRebalancing) {
        this.availabilityZoneRebalancing = availabilityZoneRebalancing;
    }

    public Map<String, Object> getServiceConnectConfiguration() { return serviceConnectConfiguration; }
    public void setServiceConnectConfiguration(Map<String, Object> serviceConnectConfiguration) {
        this.serviceConnectConfiguration = serviceConnectConfiguration;
    }

}
