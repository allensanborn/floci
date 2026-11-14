package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LocationType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class Ec2M5CatalogIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "us-east-1", "eu-west-1"})
    void m5XlargeIsOfferedInEveryZoneAndAtEveryLocationType(String regionName) {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.of(regionName))) {
            List<AvailabilityZone> zones = ec2.describeAvailabilityZones(r -> r.filters(
                    f -> f.name("state").values("available"),
                    f -> f.name("zone-type").values("availability-zone"))).availabilityZones();
            Set<String> zoneNames = zones.stream().map(AvailabilityZone::zoneName).collect(Collectors.toSet());
            Set<String> zoneIds = zones.stream().map(AvailabilityZone::zoneId).collect(Collectors.toSet());

            assertEquals(zoneNames, locations(ec2, LocationType.AVAILABILITY_ZONE, zoneNames));
            assertEquals(zoneIds, locations(ec2, LocationType.AVAILABILITY_ZONE_ID, zoneIds));
            assertEquals(Set.of(regionName), locations(ec2, LocationType.REGION, Set.of(regionName)));
        }
    }

    private static Set<String> locations(Ec2Client ec2, LocationType type, Set<String> locations) {
        return ec2.describeInstanceTypeOfferings(r -> r.locationType(type).filters(
                        f -> f.name("instance-type").values("m5.xlarge"),
                        f -> f.name("location").values(locations)))
                .instanceTypeOfferings().stream().map(InstanceTypeOffering::location)
                .collect(Collectors.toSet());
    }
}
