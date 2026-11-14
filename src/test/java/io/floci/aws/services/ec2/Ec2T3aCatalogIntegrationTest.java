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
class Ec2T3aCatalogIntegrationTest {

    @ParameterizedTest
    @CsvSource({
            "t3.large,2,8192,Intel,3,12",
            "t3.xlarge,4,16384,Intel,4,15",
            "t3.2xlarge,8,32768,Intel,4,15",
            "t3a.micro,2,1024,AMD,2,2",
            "t3a.small,2,2048,AMD,2,4",
            "t3a.medium,2,4096,AMD,3,6",
            "t3a.large,2,8192,AMD,3,12",
            "t3a.xlarge,4,16384,AMD,4,15",
            "t3a.2xlarge,8,32768,AMD,4,15"})
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
