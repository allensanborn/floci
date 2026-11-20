package io.floci.aws.services.rds;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;

import java.util.List;

import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AWS stores a DB subnet group's name as a lowercase string: Create answers and Describe reports it in
 * lower case, and a mixed-case name on a later request finds the group.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsSubnetGroupNameCaseIntegrationTest {

    private static final String ACCOUNT = "135792468013";

    @Test
    void aMixedCaseSubnetGroupNameIsStoredAndFoundInLowerCase() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1);
             RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            String vpcId = ec2.createVpc(request -> request.cidrBlock("10.51.0.0/16")).vpc().vpcId();
            List<String> subnets = List.of(subnet(ec2, vpcId, "10.51.1.0/24", "us-east-1a"),
                    subnet(ec2, vpcId, "10.51.2.0/24", "us-east-1b"));

            assertEquals("casegroup", rds.createDBSubnetGroup(request -> request.dbSubnetGroupName("CaseGroup")
                    .dbSubnetGroupDescription("d").subnetIds(subnets)).dbSubnetGroup().dbSubnetGroupName());
            assertEquals(List.of("casegroup"), rds.describeDBSubnetGroups(request -> request
                    .dbSubnetGroupName("CaseGroup")).dbSubnetGroups().stream().map(g -> g.dbSubnetGroupName()).toList());

            assertEquals("casegroup", rds.createDBCluster(request -> request.dbClusterIdentifier("case-cluster")
                    .engine("aurora-postgresql").masterUsername("dbadmin").masterUserPassword("password123")
                    .dbSubnetGroupName("CaseGroup")).dbCluster().dbSubnetGroup());
            assertEquals("casegroup", rds.createDBInstance(request -> request.dbInstanceIdentifier("case-db")
                    .engine("postgres").dbInstanceClass("db.t3.micro").allocatedStorage(20)
                    .masterUsername("dbadmin").masterUserPassword("password123")
                    .dbSubnetGroupName("CaseGroup")).dbInstance().dbSubnetGroup().dbSubnetGroupName());

            // ModifyDBSubnetGroup and ModifyDBInstance find the group by any case, and the instance keeps
            // the stored name.
            List<String> moreSubnets = List.of(subnet(ec2, vpcId, "10.51.3.0/24", "us-east-1a"),
                    subnet(ec2, vpcId, "10.51.4.0/24", "us-east-1b"));
            assertEquals("casegroup", rds.modifyDBSubnetGroup(request -> request.dbSubnetGroupName("CASEGROUP")
                    .subnetIds(moreSubnets)).dbSubnetGroup().dbSubnetGroupName());
            rds.createDBSubnetGroup(request -> request.dbSubnetGroupName("OtherGroup")
                    .dbSubnetGroupDescription("d").subnetIds(moreSubnets));
            assertEquals("othergroup", rds.modifyDBInstance(request -> request.dbInstanceIdentifier("case-db")
                    .dbSubnetGroupName("OtherGroup")).dbInstance().dbSubnetGroup().dbSubnetGroupName());
            assertEquals("othergroup", rds.describeDBInstances(request -> request.dbInstanceIdentifier("case-db"))
                    .dbInstances().getFirst().dbSubnetGroup().dbSubnetGroupName());

            rds.deleteDBInstance(request -> request.dbInstanceIdentifier("case-db").skipFinalSnapshot(true));
            rds.deleteDBCluster(request -> request.dbClusterIdentifier("case-cluster").skipFinalSnapshot(true));
            rds.deleteDBSubnetGroup(request -> request.dbSubnetGroupName("CaseGroup"));
            rds.deleteDBSubnetGroup(request -> request.dbSubnetGroupName("OtherGroup"));
            assertEquals(0, rds.describeDBSubnetGroups().dbSubnetGroups().stream()
                    .filter(g -> g.dbSubnetGroupName().equals("casegroup")).count());
        }
    }

    private static String subnet(Ec2Client ec2, String vpcId, String cidr, String zone) {
        return ec2.createSubnet(request -> request.vpcId(vpcId).cidrBlock(cidr).availabilityZone(zone))
                .subnet().subnetId();
    }
}
