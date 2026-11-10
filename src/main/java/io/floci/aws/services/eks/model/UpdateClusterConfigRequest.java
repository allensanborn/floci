package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateClusterConfigRequest {

    @JsonProperty("resourcesVpcConfig")
    private ResourcesVpcConfig resourcesVpcConfig;

    @JsonProperty("logging")
    private Logging logging;

    @JsonProperty("accessConfig")
    private AccessConfig accessConfig;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    /** Members Floci stores verbatim and echoes from DescribeCluster (upgradePolicy, computeConfig, ...). */
    private final Map<String, Object> other = new LinkedHashMap<>();

    public ResourcesVpcConfig getResourcesVpcConfig() { return resourcesVpcConfig; }
    public void setResourcesVpcConfig(ResourcesVpcConfig resourcesVpcConfig) { this.resourcesVpcConfig = resourcesVpcConfig; }

    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }

    public AccessConfig getAccessConfig() { return accessConfig; }
    public void setAccessConfig(AccessConfig accessConfig) { this.accessConfig = accessConfig; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public Map<String, Object> getOther() { return other; }

    @JsonAnySetter
    public void putOther(String key, Object value) { other.put(key, value); }
}
