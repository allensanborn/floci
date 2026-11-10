package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KubernetesNetworkConfig {

    @JsonProperty("serviceIpv4Cidr")
    private String serviceIpv4Cidr;

    @JsonProperty("ipFamily")
    private String ipFamily;

    /** EKS Auto Mode load balancing, e.g. {@code {"enabled": true}}; stored as sent. */
    @JsonProperty("elasticLoadBalancing")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> elasticLoadBalancing;

    public KubernetesNetworkConfig() {}

    public Map<String, Object> getElasticLoadBalancing() { return elasticLoadBalancing; }
    public void setElasticLoadBalancing(Map<String, Object> elasticLoadBalancing) { this.elasticLoadBalancing = elasticLoadBalancing; }

    public String getServiceIpv4Cidr() { return serviceIpv4Cidr; }
    public void setServiceIpv4Cidr(String serviceIpv4Cidr) { this.serviceIpv4Cidr = serviceIpv4Cidr; }

    public String getIpFamily() { return ipFamily; }
    public void setIpFamily(String ipFamily) { this.ipFamily = ipFamily; }
}
