package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;

import static io.floci.aws.testing.CfnRequests.deploy;
import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class CloudFormationRdsManagedPasswordIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261119/us-east-1/cloudformation/aws4_request";
    private static final String ACCOUNT = "000000000000";

    private static String template(String clusterId, String managed) {
        return """
                {"Resources": {"Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                  "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                  "MasterUsername": "dbadmin", "ManageMasterUserPassword": %s}}}}
                """.formatted(clusterId, managed);
    }

    @Test
    void managedPasswordClusterReportsMasterUserSecretThatExists() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-mp-" + suffix;
        String clusterId = "cfn-mp-cluster-" + suffix;
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT);
             SecretsManagerClient secrets = SecretsManagerClient.builder()
                     .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                     .httpClientBuilder(UrlConnectionHttpClient.builder())
                     .region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(ACCOUNT, "test-secret-key")))
                     .build()) {
            try {
                deploy("CreateStack", stackName, template(clusterId, "true"), CFN_AUTH, "CREATE_COMPLETE");
                DBCluster cluster = rds.describeDBClusters(r -> r.dbClusterIdentifier(clusterId))
                        .dbClusters().getFirst();
                assertNotNull(cluster.masterUserSecret());
                String arn = cluster.masterUserSecret().secretArn();
                assertNotNull(arn);
                assertEquals(arn, secrets.describeSecret(r -> r.secretId(arn)).arn());
            } finally {
                CfnStackWaits.deleteStack(stackName, CFN_AUTH);
            }
        }
    }

    @Test
    void unmanagedClusterHasNoMasterUserSecret() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-nomp-" + suffix;
        String clusterId = "cfn-nomp-cluster-" + suffix;
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            try {
                deploy("CreateStack", stackName,
                        template(clusterId, "false").replace("\"ManageMasterUserPassword\": false",
                                "\"MasterUserPassword\": \"password123\""),
                        CFN_AUTH, "CREATE_COMPLETE");
                assertNull(rds.describeDBClusters(r -> r.dbClusterIdentifier(clusterId))
                        .dbClusters().getFirst().masterUserSecret());
            } finally {
                CfnStackWaits.deleteStack(stackName, CFN_AUTH);
            }
        }
    }
}
