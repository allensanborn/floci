package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

@ApplicationScoped
public class DmsJsonHandler {

    private final DmsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public DmsJsonHandler(DmsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateReplicationSubnetGroup" -> {
                ObjectNode response = objectMapper.createObjectNode();
                response.set("ReplicationSubnetGroup",
                        subnetGroup(service.createReplicationSubnetGroup(request, region)));
                yield Response.ok(response).build();
            }
            case "DescribeReplicationSubnetGroups" -> {
                List<ReplicationSubnetGroup> groups = service.describeReplicationSubnetGroups(request, region);
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode items = response.putArray("ReplicationSubnetGroups");
                groups.forEach(group -> items.add(subnetGroup(group)));
                yield Response.ok(response).build();
            }
            case "DeleteReplicationSubnetGroup" -> {
                service.deleteReplicationSubnetGroup(request, region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            default -> null;
        };
    }

    private ObjectNode subnetGroup(ReplicationSubnetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ReplicationSubnetGroupIdentifier", group.getReplicationSubnetGroupIdentifier());
        node.put("ReplicationSubnetGroupDescription", group.getReplicationSubnetGroupDescription());
        node.put("VpcId", group.getVpcId());
        node.put("SubnetGroupStatus", group.getSubnetGroupStatus());
        ArrayNode subnets = node.putArray("Subnets");
        group.getSubnetIds().forEach(subnetId -> {
            ObjectNode subnet = subnets.addObject();
            subnet.put("SubnetIdentifier", subnetId);
            subnet.putObject("SubnetAvailabilityZone")
                    .put("Name", group.getSubnetAvailabilityZones().get(subnetId));
            subnet.put("SubnetStatus", "Active");
        });
        ArrayNode networkTypes = node.putArray("SupportedNetworkTypes");
        group.getSupportedNetworkTypes().forEach(networkTypes::add);
        return node;
    }
}
