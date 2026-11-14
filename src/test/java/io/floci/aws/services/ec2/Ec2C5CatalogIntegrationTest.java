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
class Ec2C5CatalogIntegrationTest {

    @ParameterizedTest
    @CsvSource({
            "c5.large,2,4096,Intel,3,10",
            "c5.xlarge,4,8192,Intel,4,15",
            "c5.2xlarge,8,16384,Intel,4,15",
            "c5.4xlarge,16,32768,Intel,8,30",
            "c5.9xlarge,36,73728,Intel,8,30",
            "c5.12xlarge,48,98304,Intel,8,30",
            "c5.18xlarge,72,147456,Intel,15,50",
            "c5.24xlarge,96,196608,Intel,15,50",
            "c4.large,2,3840,Intel,3,10",
            "c6i.large,2,4096,Intel,3,10",
            "c6i.xlarge,4,8192,Intel,4,15",
            "c6i.2xlarge,8,16384,Intel,4,15",
            "c6i.4xlarge,16,32768,Intel,8,30"})
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
