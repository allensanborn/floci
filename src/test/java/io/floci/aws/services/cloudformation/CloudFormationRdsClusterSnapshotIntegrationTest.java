package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.floci.aws.testing.CfnRequests.cfn;
import static io.floci.aws.testing.CfnRequests.deploy;
import static io.floci.aws.testing.CfnRequests.describe;
import static io.floci.aws.testing.CfnRequests.output;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** AWS::RDS::ClusterSnapshot is a real RDS snapshot: Ref and GetAtt are its ARN, and DeleteStack removes it. */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class CloudFormationRdsClusterSnapshotIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261120/us-east-1/cloudformation/aws4_request";

    @Test
    void stackSnapshotIsARealSnapshotAndDeleteStackRemovesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-snap-" + suffix;
        String clusterId = "cfn-snap-cluster-" + suffix;
        String snapshotId = "cfn-snap-" + suffix;
        String template = """
                {"Resources": {
                  "Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                    "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                    "MasterUsername": "dbadmin", "MasterUserPassword": "password123"}},
                  "Snap": {"Type": "AWS::RDS::ClusterSnapshot", "Properties": {
                    "DBClusterIdentifier": {"Ref": "Cluster"}, "DBClusterSnapshotIdentifier": "%s"}}},
                 "Outputs": {
                  "SnapRef": {"Value": {"Ref": "Snap"}},
                  "SnapArn": {"Value": {"Fn::GetAtt": ["Snap", "DBClusterSnapshotArn"]}}}}
                """.formatted(clusterId, snapshotId);
        try {
            deploy("CreateStack", stackName, template, CFN_AUTH, "CREATE_COMPLETE");

            String arn = rds("DescribeDBClusterSnapshots").formParam("DBClusterSnapshotIdentifier", snapshotId)
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<DBClusterIdentifier>" + clusterId + "</DBClusterIdentifier>"))
                    .extract().xmlPath().getString("**.find { it.name() == 'DBClusterSnapshotArn' }");
            assertFalse(arn.isBlank());
            XmlPath stack = describe(stackName, CFN_AUTH);
            assertEquals(arn, output(stack, "SnapRef"));
            assertEquals(arn, output(stack, "SnapArn"));

            cfn("DeleteStack", stackName).header("Authorization", CFN_AUTH)
                    .when().post("/").then().statusCode(200);
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                    rds("DescribeDBClusterSnapshots").formParam("DBClusterSnapshotIdentifier", snapshotId)
                            .when().post("/").then().statusCode(404)
                            .body(containsString("DBClusterSnapshot not found: " + snapshotId)));
        } finally {
            cfn("DeleteStack", stackName).header("Authorization", CFN_AUTH).when().post("/");
        }
    }
}
