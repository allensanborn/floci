package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Route {

    private String destinationCidrBlock;
    // A route has exactly one destination, and it is IPv6 for a route created with
    // DestinationIpv6CidrBlock. Keeping it in its own field rather than overloading
    // destinationCidrBlock is what stops an IPv6 route from being stored with a null
    // IPv4 destination -- which used to make every later DeleteRoute on the same table
    // fail with a NullPointerException, including deletes of unrelated IPv4 routes.
    private String destinationIpv6CidrBlock;
    private String gatewayId;
    private String natGatewayId;
    private String state = "active";
    private String origin;

    public Route() {}

    public Route(String destinationCidrBlock, String gatewayId, String origin) {
        this.destinationCidrBlock = destinationCidrBlock;
        this.gatewayId = gatewayId;
        this.origin = origin;
    }

    public String getDestinationCidrBlock() { return destinationCidrBlock; }
    public void setDestinationCidrBlock(String destinationCidrBlock) { this.destinationCidrBlock = destinationCidrBlock; }

    public String getDestinationIpv6CidrBlock() { return destinationIpv6CidrBlock; }
    public void setDestinationIpv6CidrBlock(String destinationIpv6CidrBlock) {
        this.destinationIpv6CidrBlock = destinationIpv6CidrBlock;
    }

    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }

    public String getNatGatewayId() { return natGatewayId; }
    public void setNatGatewayId(String natGatewayId) { this.natGatewayId = natGatewayId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
}
