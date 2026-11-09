package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * PreferredBackupWindow, PreferredMaintenanceWindow and VpcSecurityGroupIds round-trip from
 * CreateDBCluster/ModifyDBCluster to DescribeDBClusters, so a Terraform plan after apply is clean.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterWindowsAndSecurityGroupsIntegrationTest {

    private static final String ID = "ws-cluster";

    @AfterEach
    void cleanUp() {
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    private static void createCluster(boolean withSettings) {
        RequestSpecification req = rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password");
        if (withSettings) {
            req = req.formParam("PreferredBackupWindow", "06:00-07:00")
                    .formParam("PreferredMaintenanceWindow", "sun:07:00-sun:08:00")
                    .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-aaaa1111")
                    .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.2", "sg-bbbb2222");
        }
        req.when().post("/").then().statusCode(200);
    }

    private static void describeAndExpectSettings() {
        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<PreferredBackupWindow>06:00-07:00</PreferredBackupWindow>"))
                .body(containsString("<PreferredMaintenanceWindow>sun:07:00-sun:08:00</PreferredMaintenanceWindow>"))
                .body(containsString("<VpcSecurityGroupId>sg-aaaa1111</VpcSecurityGroupId>"))
                .body(containsString("<VpcSecurityGroupId>sg-bbbb2222</VpcSecurityGroupId>"))
                .body(not(containsString("sg-00000000")));
    }

    @Test
    void settingsGivenOnCreateAreDescribed() {
        createCluster(true);
        describeAndExpectSettings();
    }

    @Test
    void settingsGivenOnModifyAreDescribed() {
        createCluster(false);
        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("PreferredBackupWindow", "06:00-07:00")
                .formParam("PreferredMaintenanceWindow", "sun:07:00-sun:08:00")
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-aaaa1111")
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.2", "sg-bbbb2222")
                .when().post("/").then().statusCode(200)
                .body(containsString("<PreferredBackupWindow>06:00-07:00</PreferredBackupWindow>"));
        describeAndExpectSettings();
    }

    @Test
    void defaultsApplyWhenNothingGiven() {
        createCluster(false);
        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<PreferredBackupWindow>04:00-06:00</PreferredBackupWindow>"))
                .body(containsString("<PreferredMaintenanceWindow>mon:00:00-mon:03:00</PreferredMaintenanceWindow>"))
                .body(containsString("<VpcSecurityGroupId>sg-00000000</VpcSecurityGroupId>"));
    }
}
