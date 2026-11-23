package io.floci.aws.services.docdb;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;

/**
 * The settings a DocumentDB cluster is created with come back from DescribeDBClusters, as on a
 * live account: through the Query protocol, with a real KMS key from the KMS store and the
 * default subnet group, parameter group and security group.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class DocDbClusterSettingsIntegrationTest {

    private static final String ID = "settings-cluster";

    @Inject
    KmsService kmsService;

    @Inject
    Ec2Service ec2Service;

    @AfterEach
    void cleanUp() {
        rds("DeleteDBInstance").formParam("DBInstanceIdentifier", ID + "-1").when().post("/");
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID).formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    /**
     * A security group that exists is the case a live stack always sends, and the one a probe
     * with a made-up id never reaches, because an unknown id is refused before its record is
     * consulted. 2.0.1 answered it with an internal error.
     */
    @Test
    void anExistingSecurityGroupIsAcceptedOnCreateAndModify() {
        String vpcId = ec2Service.createVpc("us-east-1", "10.9.0.0/16", false).getVpcId();
        SecurityGroup first = ec2Service.createSecurityGroup("us-east-1", "docdb-first", "d", vpcId);
        SecurityGroup second = ec2Service.createSecurityGroup("us-east-1", "docdb-second", "d", vpcId);

        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "u")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", first.getGroupId())
                .when().post("/").then().statusCode(200)
                .body(containsString("<VpcSecurityGroupId>" + first.getGroupId() + "</VpcSecurityGroupId>"));

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", first.getGroupId())
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.2", second.getGroupId())
                .when().post("/").then().statusCode(200);

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<VpcSecurityGroupId>" + first.getGroupId() + "</VpcSecurityGroupId>"))
                .body(containsString("<VpcSecurityGroupId>" + second.getGroupId() + "</VpcSecurityGroupId>"));
    }

    /** The group's name is stored lower case by RDS, and the cluster reports that name, not the spelling sent. */
    @Test
    void aMixedCaseSubnetGroupNameIsStoredAsTheGroupsLowerCaseName() {
        String vpcId = ec2Service.createVpc("us-east-1", "10.8.0.0/16", false).getVpcId();
        String a = ec2Service.createSubnet("us-east-1", vpcId, "10.8.1.0/24", "us-east-1a").getSubnetId();
        String b = ec2Service.createSubnet("us-east-1", vpcId, "10.8.2.0/24", "us-east-1b").getSubnetId();
        rds("CreateDBSubnetGroup").formParam("DBSubnetGroupName", "DocMixedGroup")
                .formParam("DBSubnetGroupDescription", "d")
                .formParam("SubnetIds.SubnetIdentifier.1", a).formParam("SubnetIds.SubnetIdentifier.2", b)
                .when().post("/").then().statusCode(200);
        try {
            rds("CreateDBCluster").formParam("DBClusterIdentifier", ID).formParam("Engine", "docdb")
                    .formParam("MasterUsername", "u").formParam("MasterUserPassword", "secret99password")
                    .formParam("DBSubnetGroupName", "DocMixedGroup")
                    .when().post("/").then().statusCode(200);
            rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<DBSubnetGroup>docmixedgroup</DBSubnetGroup>"));
        } finally {
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID).formParam("SkipFinalSnapshot", "true")
                    .when().post("/");
            rds("DeleteDBSubnetGroup").formParam("DBSubnetGroupName", "DocMixedGroup").when().post("/");
        }
    }

    @Test
    void settingsGivenOnCreateComeBackFromDescribeAndModifyChangesThem() {
        String keyArn = kmsService.createKey("docdb settings", "us-east-1").getArn();

        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "u")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("StorageEncrypted", "true")
                .formParam("KmsKeyId", keyArn)
                .formParam("BackupRetentionPeriod", "5")
                .formParam("PreferredBackupWindow", "23:30-00:00")
                .formParam("PreferredMaintenanceWindow", "Sun:03:00-Sun:04:00")
                .formParam("Tags.Tag.1.Key", "Name")
                .formParam("Tags.Tag.1.Value", ID)
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<KmsKeyId>" + keyArn + "</KmsKeyId>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<DBSubnetGroup>default</DBSubnetGroup>"))
            .body(containsString("<DBClusterParameterGroup>default.docdb5.0</DBClusterParameterGroup>"))
            .body(containsString("<StorageEncrypted>true</StorageEncrypted>"))
            .body(containsString("<KmsKeyId>" + keyArn + "</KmsKeyId>"))
            .body(containsString("<BackupRetentionPeriod>5</BackupRetentionPeriod>"))
            .body(containsString("<PreferredBackupWindow>23:30-00:00</PreferredBackupWindow>"))
            .body(containsString("<PreferredMaintenanceWindow>sun:03:00-sun:04:00</PreferredMaintenanceWindow>"))
            .body(containsString("<VpcSecurityGroupId>sg-"));

        rds("ListTagsForResource").formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:cluster:" + ID)
        .when().post("/").then().statusCode(200).body(containsString("<Value>" + ID + "</Value>"));

        rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", ID + "-1")
                .formParam("DBInstanceClass", "db.t3.medium")
                .formParam("Engine", "docdb")
                .formParam("DBClusterIdentifier", ID)
                .formParam("AutoMinorVersionUpgrade", "false")
                .formParam("Tags.Tag.1.Key", "Name")
                .formParam("Tags.Tag.1.Value", ID + "-1")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<AutoMinorVersionUpgrade>false</AutoMinorVersionUpgrade>"))
            .body(containsString("<PreferredMaintenanceWindow>sun:03:00-sun:04:00</PreferredMaintenanceWindow>"))
            .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));

        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("BackupRetentionPeriod", "7")
                .formParam("PreferredBackupWindow", "02:00-02:30")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"))
            .body(containsString("<PreferredBackupWindow>02:00-02:30</PreferredBackupWindow>"));

        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredMaintenanceWindow", "mon:02:15-mon:02:45")
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("The backup window and maintenance window must not overlap."));
    }
}
