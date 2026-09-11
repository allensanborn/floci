package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.databasemigration.DatabaseMigrationClient;
import software.amazon.awssdk.services.databasemigration.model.DatabaseMigrationException;
import software.amazon.awssdk.services.databasemigration.model.ReplicationSubnetGroup;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Subnet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmsTest {

    private static final String GROUP_ID = "sdk-compat-subnet-group";

    @Test
    void replicationSubnetGroupLifecycleUsesAwsSdkWireContract() {
        List<Subnet> subnets = twoSubnetsInOneVpcAcrossZones();
        List<String> subnetIds = subnets.stream().map(Subnet::subnetId).toList();

        try (DatabaseMigrationClient dms = TestFixtures.databaseMigrationClient()) {
            dms.createReplicationSubnetGroup(request -> request
                    .replicationSubnetGroupIdentifier(GROUP_ID)
                    .replicationSubnetGroupDescription("sdk compatibility suite")
                    .subnetIds(subnetIds));
            try {
                List<ReplicationSubnetGroup> described = dms.describeReplicationSubnetGroups(request -> request
                                .filters(filter -> filter.name("replication-subnet-group-id").values(GROUP_ID)))
                        .replicationSubnetGroups();

                assertEquals(1, described.size());
                ReplicationSubnetGroup group = described.getFirst();
                assertEquals(GROUP_ID, group.replicationSubnetGroupIdentifier());
                assertEquals("Complete", group.subnetGroupStatus());
                assertEquals(subnets.getFirst().vpcId(), group.vpcId());
                assertFalse(group.subnets().isEmpty());
                assertTrue(group.subnets().stream()
                        .allMatch(subnet -> "Active".equals(subnet.subnetStatus())));
            } finally {
                dms.deleteReplicationSubnetGroup(request -> request.replicationSubnetGroupIdentifier(GROUP_ID));
            }

            DatabaseMigrationException deleted = assertThrows(DatabaseMigrationException.class,
                    () -> dms.describeReplicationSubnetGroups(request -> request
                            .filters(filter -> filter.name("replication-subnet-group-id").values(GROUP_ID))));
            assertEquals("ResourceNotFoundFault", deleted.awsErrorDetails().errorCode());
        }
    }

    /**
     * DMS requires the group's subnets to share a VPC and to cover at least two Availability
     * Zones, so pick a VPC that satisfies both rather than the first two subnets returned.
     */
    private static List<Subnet> twoSubnetsInOneVpcAcrossZones() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Map<String, Map<String, Subnet>> byVpcAndZone = new LinkedHashMap<>();
            for (Subnet subnet : ec2.describeSubnets().subnets()) {
                byVpcAndZone
                        .computeIfAbsent(subnet.vpcId(), vpc -> new LinkedHashMap<>())
                        .putIfAbsent(subnet.availabilityZone(), subnet);
            }
            return byVpcAndZone.values().stream()
                    .filter(byZone -> byZone.size() >= 2)
                    .map(byZone -> byZone.values().stream().limit(2).toList())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no VPC has subnets in two Availability Zones"));
        }
    }
}
