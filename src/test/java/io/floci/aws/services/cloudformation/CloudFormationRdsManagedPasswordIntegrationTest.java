package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.net.URI;

import static io.floci.aws.testing.CfnRequests.deploy;
import static io.floci.aws.testing.CfnRequests.describe;
import static io.floci.aws.testing.CfnRequests.output;
import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class CloudFormationRdsManagedPasswordIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261119/us-east-1/cloudformation/aws4_request";
    private static final String ACCOUNT = "000000000000";

    /** A cluster with the given extra properties; the key is a KMS key the stack creates. */
    private static String template(String clusterId, String clusterProps) {
        return """
                {"Resources": {
                  "Key": {"Type": "AWS::KMS::Key", "Properties": {"KeyPolicy": {"Version": "2012-10-17",
                    "Statement": [{"Effect": "Allow", "Principal": "*", "Action": "kms:*", "Resource": "*"}]}}},
                  "Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                  "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql", "MasterUsername": "dbadmin", %s}}},
                 "Outputs": {"SecretArn": {"Value": {"Fn::GetAtt": ["Cluster", "MasterUserSecret.SecretArn"]}},
                             "KeyArn": {"Value": {"Fn::GetAtt": ["Key", "Arn"]}}}}
                """.formatted(clusterId, clusterProps);
    }

    private static final String MANAGED = "\"ManageMasterUserPassword\": true";
    private static final String UNMANAGED = "\"MasterUserPassword\": \"password123\"";

    private static SecretsManagerClient secretsClient() {
        return SecretsManagerClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCOUNT, "test-secret-key")))
                .build();
    }

    private static DBCluster cluster(RdsClient rds, String id) {
        return rds.describeDBClusters(r -> r.dbClusterIdentifier(id)).dbClusters().getFirst();
    }

    private static boolean secretGone(SecretsManagerClient secrets, String arn) {
        try {
            return secrets.describeSecret(r -> r.secretId(arn)).deletedDate() != null;
        } catch (ResourceNotFoundException e) {
            return true;
        }
    }

    @Test
    void managedClusterReportsSecretOutputAndKmsKeyAndStackDeleteRemovesTheSecret() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-mp-" + suffix;
        String clusterId = "cfn-mp-cluster-" + suffix;
        String arn = null;
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT); SecretsManagerClient secrets = secretsClient()) {
            try {
                deploy("CreateStack", stackName, template(clusterId,
                        MANAGED + ", \"MasterUserSecret\": {\"KmsKeyId\": {\"Fn::GetAtt\": [\"Key\", \"Arn\"]}}"),
                        CFN_AUTH, "CREATE_COMPLETE");
                DBCluster cluster = cluster(rds, clusterId);
                arn = cluster.masterUserSecret().secretArn();
                assertNotNull(arn);
                assertEquals(arn, secrets.describeSecret(r -> r.secretId(
                        cluster.masterUserSecret().secretArn())).arn());
                XmlPath stack = describe(stackName, CFN_AUTH);
                assertEquals(arn, output(stack, "SecretArn"));
                assertEquals(output(stack, "KeyArn"), cluster.masterUserSecret().kmsKeyId());
            } finally {
                CfnStackWaits.deleteStack(stackName, CFN_AUTH);
            }
            assertNotNull(arn);
            assertTrue(secretGone(secrets, arn));
        }
    }

    @Test
    void updateFlipsManagedPasswordBothWays() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-flip-" + suffix;
        String clusterId = "cfn-flip-cluster-" + suffix;
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            try {
                deploy("CreateStack", stackName, template(clusterId, UNMANAGED), CFN_AUTH, "CREATE_COMPLETE");
                assertNull(cluster(rds, clusterId).masterUserSecret());
                deploy("UpdateStack", stackName, template(clusterId, MANAGED), CFN_AUTH, "UPDATE_COMPLETE");
                assertNotNull(cluster(rds, clusterId).masterUserSecret());
                assertEquals(cluster(rds, clusterId).masterUserSecret().secretArn(),
                        output(describe(stackName, CFN_AUTH), "SecretArn"));
                deploy("UpdateStack", stackName, template(clusterId, UNMANAGED), CFN_AUTH, "UPDATE_COMPLETE");
                assertNull(cluster(rds, clusterId).masterUserSecret());
            } finally {
                CfnStackWaits.deleteStack(stackName, CFN_AUTH);
            }
        }
    }

    @Test
    void unrelatedUpdateKeepsASecretManagementTurnedOnOutsideTheStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-out-" + suffix;
        String clusterId = "cfn-out-cluster-" + suffix;
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            try {
                deploy("CreateStack", stackName, template(clusterId, UNMANAGED), CFN_AUTH, "CREATE_COMPLETE");
                rds.modifyDBCluster(r -> r.dbClusterIdentifier(clusterId).manageMasterUserPassword(true)
                        .applyImmediately(true));
                String arn = cluster(rds, clusterId).masterUserSecret().secretArn();
                deploy("UpdateStack", stackName, template(clusterId, "\"MasterUserPassword\": \"password456\""),
                        CFN_AUTH, "UPDATE_COMPLETE");
                assertEquals(arn, cluster(rds, clusterId).masterUserSecret().secretArn());
            } finally {
                CfnStackWaits.deleteStack(stackName, CFN_AUTH);
            }
        }
    }

    @Test
    void updateAppliesMasterUserSecretKmsKeyId() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-ukey-" + suffix;
        String clusterId = "cfn-ukey-cluster-" + suffix;
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT)) {
            try {
                deploy("CreateStack", stackName, template(clusterId, UNMANAGED), CFN_AUTH, "CREATE_COMPLETE");
                deploy("UpdateStack", stackName, template(clusterId,
                        MANAGED + ", \"MasterUserSecret\": {\"KmsKeyId\": {\"Fn::GetAtt\": [\"Key\", \"Arn\"]}}"),
                        CFN_AUTH, "UPDATE_COMPLETE");
                assertEquals(output(describe(stackName, CFN_AUTH), "KeyArn"),
                        cluster(rds, clusterId).masterUserSecret().kmsKeyId());
            } finally {
                CfnStackWaits.deleteStack(stackName, CFN_AUTH);
            }
        }
    }
}
