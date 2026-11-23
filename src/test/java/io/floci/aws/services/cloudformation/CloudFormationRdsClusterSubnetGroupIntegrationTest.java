package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code DBSubnetGroupName} on {@code AWS::RDS::DBCluster} places the cluster in the subnet
 * group's VPC rather than the default one.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class CloudFormationRdsClusterSubnetGroupIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261014/us-east-1/cloudformation/aws4_request";
    private static final String RDS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261014/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261014/us-east-1/ec2/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    @Test
    void clusterLandsInTheSubnetGroupsNonDefaultVpc() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cluster-subnets-" + suffix;
        String clusterId = "cfn-cluster-subnets-" + suffix;
        String groupName = "cfn-cluster-sng-" + suffix;
        String vpc = ec2("CreateVpc").formParam("CidrBlock", "10.91.0.0/16")
                .when().post("/").then().statusCode(200).extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        String subnetA = subnet(vpc, "10.91.1.0/24", "us-east-1a");
        String subnetB = subnet(vpc, "10.91.2.0/24", "us-east-1b");
        try {
            stack("CreateStack", stackName, """
                    {"Resources": {
                      "Subnets": {"Type": "AWS::RDS::DBSubnetGroup", "Properties": {
                        "DBSubnetGroupName": "%s", "DBSubnetGroupDescription": "d",
                        "SubnetIds": ["%s", "%s"]}},
                      "Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                        "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                        "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                        "DBSubnetGroupName": {"Ref": "Subnets"}}}}}
                    """.formatted(groupName, subnetA, subnetB, clusterId));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            // Read back through separate describe calls, not the stack outputs.
            XmlPath cluster = rds("DescribeDBClusters").formParam("DBClusterIdentifier", clusterId)
                    .when().post("/").then().statusCode(200).extract().xmlPath();
            assertEquals(groupName, cluster.getString("**.find { it.name() == 'DBCluster' }.DBSubnetGroup"));
            XmlPath group = rds("DescribeDBSubnetGroups").formParam("DBSubnetGroupName", groupName)
                    .when().post("/").then().statusCode(200).extract().xmlPath();
            assertEquals(vpc, group.getString("**.find { it.name() == 'DBSubnetGroup' }.VpcId"));
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    private static String subnet(String vpc, String cidr, String az) {
        return ec2("CreateSubnet").formParam("VpcId", vpc).formParam("CidrBlock", cidr)
                .formParam("AvailabilityZone", az)
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateSubnetResponse.subnet.subnetId");
    }

    private static RequestSpecification ec2(String action) {
        return given().header("Authorization", EC2_AUTH)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("Version", "2016-11-15");
    }

    private static RequestSpecification rds(String action) {
        return given().header("Authorization", RDS_AUTH)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("Version", "2014-10-31");
    }

    private static void stack(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }
}
