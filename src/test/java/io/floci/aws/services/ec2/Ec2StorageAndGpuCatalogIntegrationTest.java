package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LocationType;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class Ec2StorageAndGpuCatalogIntegrationTest {

    @ParameterizedTest
    @CsvSource({
            "i3.large,2,15616,Intel,3,10",
            "i4i.metal,128,1048576,Intel,15,50",
            "g4dn.2xlarge,8,32768,Intel,3,10",
            "p4d.24xlarge,96,1179648,Intel,15,50"})
    void typeIsDescribedAndOfferedInEveryUsWest2Zone(String type, int vcpus, long mib, String maker,
                                                    int interfaces, int ipv4PerInterface) {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_WEST_2)) {
            InstanceTypeInfo info = ec2.describeInstanceTypes(r -> r.instanceTypesWithStrings(type))
                    .instanceTypes().getFirst();
            assertEquals(vcpus, info.vCpuInfo().defaultVCpus());
            assertEquals(mib, info.memoryInfo().sizeInMiB());
            assertEquals(maker, info.processorInfo().manufacturer());
            assertEquals(interfaces, info.networkInfo().networkCards().getFirst().maximumNetworkInterfaces());
            assertEquals(ipv4PerInterface, info.networkInfo().ipv4AddressesPerInterface());

            Set<String> zones = ec2.describeAvailabilityZones().availabilityZones().stream()
                    .map(AvailabilityZone::zoneName).collect(Collectors.toSet());
            Set<String> offered = ec2.describeInstanceTypeOfferings(r -> r
                            .locationType(LocationType.AVAILABILITY_ZONE)
                            .filters(f -> f.name("instance-type").values(type),
                                    f -> f.name("location").values(zones)))
                    .instanceTypeOfferings().stream().map(InstanceTypeOffering::location)
                    .collect(Collectors.toSet());
            assertEquals(zones, offered);
        }
    }
}
