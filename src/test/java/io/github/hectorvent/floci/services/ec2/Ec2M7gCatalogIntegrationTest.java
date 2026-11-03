package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LocationType;
import software.amazon.awssdk.services.ec2.model.NetworkCardInfo;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2M7gCatalogIntegrationTest {

    // AWS general purpose specifications: https://docs.aws.amazon.com/ec2/latest/instancetypes/gp.html
    @ParameterizedTest
    @CsvSource({
            "m7g.medium, 1, 4096, 2, 4",
            "m7g.large, 2, 8192, 3, 10",
            "m7g.xlarge, 4, 16384, 4, 15",
            "m7g.2xlarge, 8, 32768, 4, 15",
    })
    void m7gMetadataAndAvailabilityZoneOfferingsAgree(String name, int vcpus, long memoryMib,
                                                       int maxInterfaces, int ipv4PerInterface) {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            List<InstanceTypeInfo> types = ec2.describeInstanceTypes(r -> r.instanceTypesWithStrings(name))
                    .instanceTypes();
            assertThat(types, hasSize(1));
            InstanceTypeInfo type = types.getFirst();
            assertEquals(name, type.instanceTypeAsString());
            assertEquals(vcpus, type.vCpuInfo().defaultVCpus());
            assertEquals(memoryMib, type.memoryInfo().sizeInMiB());
            assertThat(type.processorInfo().supportedArchitecturesAsStrings(), contains("arm64"));
            assertFalse(type.instanceStorageSupported());
            assertTrue(type.networkInfo().encryptionInTransitSupported());
            assertEquals(0, type.networkInfo().defaultNetworkCardIndex());
            assertEquals(ipv4PerInterface, type.networkInfo().ipv4AddressesPerInterface());
            assertThat(type.networkInfo().networkCards(), hasSize(1));
            NetworkCardInfo card = type.networkInfo().networkCards().getFirst();
            assertEquals(0, card.networkCardIndex());
            assertEquals(maxInterfaces, card.maximumNetworkInterfaces());

            String zone = ec2.describeAvailabilityZones().availabilityZones().get(0).zoneName();
            List<InstanceTypeOffering> offerings = ec2.describeInstanceTypeOfferings(r -> r
                    .locationType(LocationType.AVAILABILITY_ZONE)
                    .filters(
                            f -> f.name("instance-type").values(name),
                            f -> f.name("location").values(zone)))
                    .instanceTypeOfferings();
            assertThat(offerings, hasSize(1));
            assertEquals(name, offerings.getFirst().instanceTypeAsString());
            assertEquals(zone, offerings.getFirst().location());
        }
    }
}
