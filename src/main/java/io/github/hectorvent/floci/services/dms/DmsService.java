package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class DmsService implements Resettable {

    private static final Pattern SUBNET_GROUP_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String SUBNET_GROUP_ID_FILTER = "replication-subnet-group-id";
    private static final int MINIMUM_AVAILABILITY_ZONES = 2;

    private final AccountAwareStorageBackend<ReplicationSubnetGroup> subnetGroups;
    private final Ec2Service ec2Service;

    @Inject
    public DmsService(StorageFactory storageFactory, Ec2Service ec2Service) {
        this.subnetGroups = storageFactory.create("dms", "dms-replication-subnet-groups.json",
                new TypeReference<Map<String, ReplicationSubnetGroup>>() {});
        this.ec2Service = ec2Service;
    }

    public synchronized ReplicationSubnetGroup createReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireIdentifier(request);
        String description = text(request, "ReplicationSubnetGroupDescription");
        if (description == null || description.isBlank()) {
            throw invalidParameter("The parameter ReplicationSubnetGroupDescription must be provided"
                    + " and must not be blank.");
        }
        List<String> subnetIds = requireSubnetIds(request);
        if (subnetGroups.get(storageKey(region, identifier)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsFault",
                    "The resource you are attempting to create already exists.", 400);
        }

        ReplicationSubnetGroup group = buildSubnetGroup(identifier, description, subnetIds, region);
        subnetGroups.put(storageKey(region, identifier), group);
        return group;
    }

    public List<ReplicationSubnetGroup> describeReplicationSubnetGroups(JsonNode request, String region) {
        List<String> requestedIdentifiers = identifierFilters(request);
        if (!requestedIdentifiers.isEmpty()) {
            return requestedIdentifiers.stream()
                    .map(identifier -> subnetGroups.get(storageKey(region, identifier))
                            .orElseThrow(() -> notFound(identifier)))
                    .toList();
        }
        return subnetGroups.scan(key -> key.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(ReplicationSubnetGroup::getReplicationSubnetGroupIdentifier))
                .toList();
    }

    public synchronized void deleteReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireIdentifier(request);
        String key = storageKey(region, identifier);
        if (subnetGroups.get(key).isEmpty()) {
            throw notFound(identifier);
        }
        subnetGroups.delete(key);
    }

    @Override
    public void clear() {
        subnetGroups.clear();
    }

    private ReplicationSubnetGroup buildSubnetGroup(String identifier, String description,
                                                    List<String> subnetIds, String region) {
        Map<String, Subnet> resolved = new LinkedHashMap<>();
        ec2Service.describeSubnets(region, subnetIds, Map.of())
                .forEach(subnet -> resolved.put(subnet.getSubnetId(), subnet));
        List<String> requested = subnetIds.stream().distinct().toList();
        List<String> missing = requested.stream().filter(id -> !resolved.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new AwsException("InvalidSubnet",
                    "The subnet provided is invalid: " + missing + ".", 400);
        }

        // Response order follows the request rather than storage iteration order, so a describe
        // that follows a create returns the same subnet ordering every time.
        Map<String, String> availabilityZones = new LinkedHashMap<>();
        for (String subnetId : requested) {
            availabilityZones.put(subnetId, resolved.get(subnetId).getAvailabilityZone());
        }

        String vpcId = resolved.get(requested.getFirst()).getVpcId();
        boolean sameVpc = resolved.values().stream()
                .map(Subnet::getVpcId)
                .filter(Objects::nonNull)
                .allMatch(vpcId::equals);
        if (!sameVpc) {
            throw new AwsException("InvalidSubnet",
                    "The subnets provided for replication subnet group " + identifier
                            + " belong to more than one VPC.", 400);
        }

        if (Set.copyOf(availabilityZones.values()).size() < MINIMUM_AVAILABILITY_ZONES) {
            throw new AwsException("ReplicationSubnetGroupDoesNotCoverEnoughAZs",
                    "The replication subnet group does not cover enough Availability Zones (AZs)."
                            + " Edit the replication subnet group and add more AZs.", 400);
        }

        ReplicationSubnetGroup group = new ReplicationSubnetGroup();
        group.setReplicationSubnetGroupIdentifier(identifier);
        group.setReplicationSubnetGroupDescription(description);
        group.setVpcId(vpcId);
        group.setSubnetGroupStatus("Complete");
        group.setSubnetIds(requested);
        group.setSubnetAvailabilityZones(availabilityZones);
        group.setSupportedNetworkTypes(List.of("IPV4"));
        return group;
    }

    /**
     * AWS stores the identifier as a lowercase string, so every lookup normalises the same way:
     * a group created as "MyGroup" is described and deleted as "mygroup".
     */
    private static String requireIdentifier(JsonNode request) {
        String value = text(request, "ReplicationSubnetGroupIdentifier");
        if (value == null || value.isBlank()) {
            throw invalidParameter("The parameter ReplicationSubnetGroupIdentifier must be provided"
                    + " and must not be blank.");
        }
        String identifier = value.toLowerCase(Locale.ROOT);
        if (identifier.length() > 255 || !SUBNET_GROUP_IDENTIFIER.matcher(identifier).matches()) {
            throw invalidParameter("ReplicationSubnetGroupIdentifier must contain no more than 255"
                    + " alphanumeric characters, periods, underscores, or hyphens.");
        }
        if ("default".equals(identifier)) {
            throw invalidParameter("ReplicationSubnetGroupIdentifier must not be \"default\".");
        }
        return identifier;
    }

    private static List<String> requireSubnetIds(JsonNode request) {
        JsonNode node = request == null ? null : request.get("SubnetIds");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalidParameter("The parameter SubnetIds must be provided and must not be empty.");
        }
        List<String> subnetIds = new ArrayList<>();
        node.forEach(element -> {
            if (!element.isTextual() || element.textValue().isBlank()) {
                throw invalidParameter("The parameter SubnetIds must contain subnet identifiers.");
            }
            subnetIds.add(element.textValue());
        });
        return subnetIds;
    }

    private static List<String> identifierFilters(JsonNode request) {
        JsonNode filters = request == null ? null : request.get("Filters");
        if (filters == null || !filters.isArray()) {
            return List.of();
        }
        List<String> identifiers = new ArrayList<>();
        for (JsonNode filter : filters) {
            String name = text(filter, "Name");
            if (!SUBNET_GROUP_ID_FILTER.equals(name)) {
                throw invalidParameter("Invalid filter: " + name + ".");
            }
            JsonNode values = filter.get("Values");
            if (values == null || !values.isArray() || values.isEmpty()) {
                throw invalidParameter("The filter " + SUBNET_GROUP_ID_FILTER + " must have values.");
            }
            values.forEach(value -> identifiers.add(value.asText().toLowerCase(Locale.ROOT)));
        }
        return identifiers;
    }

    private static AwsException notFound(String identifier) {
        return new AwsException("ResourceNotFoundFault",
                "Replication subnet group " + identifier + " not found.", 400);
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static String storageKey(String region, String identifier) {
        return region + "::" + identifier;
    }
}
