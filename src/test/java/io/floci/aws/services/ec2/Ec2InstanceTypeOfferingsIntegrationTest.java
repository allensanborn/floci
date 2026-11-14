package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LocationType;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class Ec2InstanceTypeOfferingsIntegrationTest {

    @Test
    void zoneIdLocationTypeListsTheZoneIdsDescribeAvailabilityZonesReports() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_WEST_2)) {
            Set<String> zoneIds = ec2.describeAvailabilityZones().availabilityZones().stream()
                    .map(AvailabilityZone::zoneId).collect(Collectors.toSet());
            Set<String> offered = ec2.describeInstanceTypeOfferings(r -> r
                            .locationType(LocationType.AVAILABILITY_ZONE_ID)
                            .filters(f -> f.name("instance-type").values("t3.micro"),
                                    f -> f.name("location").values(zoneIds)))
                    .instanceTypeOfferings().stream().map(InstanceTypeOffering::location)
                    .collect(Collectors.toSet());
            assertEquals(zoneIds, offered);
        }
    }
}
