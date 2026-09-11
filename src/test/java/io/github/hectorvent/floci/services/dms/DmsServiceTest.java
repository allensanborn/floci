package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DmsServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String VPC_ID = "vpc-0dms";

    private final ObjectMapper mapper = new ObjectMapper();
    private DmsService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<ReplicationSubnetGroup> store =
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("dms"), eq("dms-replication-subnet-groups.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) store);

        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.describeSubnets(eq(REGION), anyList(), anyMap())).thenAnswer(invocation -> {
            List<String> requested = invocation.getArgument(1);
            return requested.stream().map(DmsServiceTest::subnet).filter(java.util.Objects::nonNull).toList();
        });
        service = new DmsService(storageFactory, ec2Service);
    }

    @Test
    void createStoresGroupUnderLowercasedIdentifier() {
        service.createReplicationSubnetGroup(
                createRequest("Tf-Example", "example", "subnet-a", "subnet-b"), REGION);

        List<ReplicationSubnetGroup> described =
                service.describeReplicationSubnetGroups(filterRequest("tf-example"), REGION);

        assertEquals(1, described.size());
        ReplicationSubnetGroup group = described.getFirst();
        assertEquals("tf-example", group.getReplicationSubnetGroupIdentifier());
        assertEquals("example", group.getReplicationSubnetGroupDescription());
        assertEquals(VPC_ID, group.getVpcId());
        assertEquals("Complete", group.getSubnetGroupStatus());
        assertEquals(List.of("subnet-a", "subnet-b"), group.getSubnetIds());
        assertEquals(Map.of("subnet-a", "us-east-1a", "subnet-b", "us-east-1b"),
                group.getSubnetAvailabilityZones());
        assertEquals(List.of("IPV4"), group.getSupportedNetworkTypes());
    }

    @Test
    void createRejectsDuplicateIdentifierRegardlessOfCase() {
        service.createReplicationSubnetGroup(createRequest("dup", "first", "subnet-a", "subnet-b"), REGION);

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("DUP", "second", "subnet-a", "subnet-b"), REGION));
        assertEquals("ResourceAlreadyExistsFault", duplicate.getErrorCode());
    }

    @Test
    void createRejectsUnknownSubnets() {
        AwsException invalid = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("bad-subnets", "example", "subnet-a", "subnet-missing"), REGION));
        assertEquals("InvalidSubnet", invalid.getErrorCode());
    }

    @Test
    void createRejectsSubnetsInASingleAvailabilityZone() {
        AwsException tooFewZones = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("one-az", "example", "subnet-a"), REGION));
        assertEquals("ReplicationSubnetGroupDoesNotCoverEnoughAZs", tooFewZones.getErrorCode());
    }

    @Test
    void createRejectsSubnetsSpanningVpcs() {
        AwsException crossVpc = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("cross-vpc", "example", "subnet-a", "subnet-other-vpc"), REGION));
        assertEquals("InvalidSubnet", crossVpc.getErrorCode());
    }

    @Test
    void createRejectsMissingDescription() {
        ObjectNode request = createRequest("no-description", null, "subnet-a", "subnet-b");
        AwsException missing = assertThrows(AwsException.class,
                () -> service.createReplicationSubnetGroup(request, REGION));
        assertEquals("InvalidParameterValueException", missing.getErrorCode());
    }

    @Test
    void createRejectsReservedDefaultIdentifier() {
        AwsException reserved = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("Default", "example", "subnet-a", "subnet-b"), REGION));
        assertEquals("InvalidParameterValueException", reserved.getErrorCode());
    }

    @Test
    void describeIsScopedToTheRequestRegion() {
        service.createReplicationSubnetGroup(createRequest("scoped", "example", "subnet-a", "subnet-b"), REGION);

        assertTrue(service.describeReplicationSubnetGroups(mapper.createObjectNode(), "eu-west-1").isEmpty());
    }

    @Test
    void describeOfMissingGroupFaults() {
        AwsException notFound = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(filterRequest("absent"), REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void deleteRemovesTheGroup() {
        service.createReplicationSubnetGroup(createRequest("doomed", "example", "subnet-a", "subnet-b"), REGION);
        service.deleteReplicationSubnetGroup(identifierRequest("doomed"), REGION);

        AwsException notFound = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(filterRequest("doomed"), REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void deleteOfMissingGroupFaults() {
        AwsException notFound = assertThrows(AwsException.class,
                () -> service.deleteReplicationSubnetGroup(identifierRequest("absent"), REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void clearRemovesPersistedState() {
        service.createReplicationSubnetGroup(createRequest("reset-me", "example", "subnet-a", "subnet-b"), REGION);
        service.clear();

        assertTrue(service.describeReplicationSubnetGroups(mapper.createObjectNode(), REGION).isEmpty());
    }

    private ObjectNode createRequest(String identifier, String description, String... subnetIds) {
        ObjectNode request = identifierRequest(identifier);
        if (description != null) {
            request.put("ReplicationSubnetGroupDescription", description);
        }
        ArrayNode subnets = request.putArray("SubnetIds");
        for (String subnetId : subnetIds) {
            subnets.add(subnetId);
        }
        return request;
    }

    private ObjectNode identifierRequest(String identifier) {
        ObjectNode request = mapper.createObjectNode();
        request.put("ReplicationSubnetGroupIdentifier", identifier);
        return request;
    }

    private ObjectNode filterRequest(String identifier) {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode filter = request.putArray("Filters").addObject();
        filter.put("Name", "replication-subnet-group-id");
        filter.putArray("Values").add(identifier);
        return request;
    }

    private static Subnet subnet(String subnetId) {
        return switch (subnetId) {
            case "subnet-a" -> subnet(subnetId, VPC_ID, "us-east-1a");
            case "subnet-b" -> subnet(subnetId, VPC_ID, "us-east-1b");
            case "subnet-other-vpc" -> subnet(subnetId, "vpc-0other", "us-east-1b");
            default -> null;
        };
    }

    private static Subnet subnet(String subnetId, String vpcId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(availabilityZone);
        subnet.setRegion(REGION);
        return subnet;
    }
}
