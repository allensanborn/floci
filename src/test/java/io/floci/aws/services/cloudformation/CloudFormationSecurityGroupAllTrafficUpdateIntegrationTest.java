package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.SecurityGroupRule;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An update that re-executes the group and a standalone rule without changing the rules must leave rules declared as all traffic
 * ({@code -1} with ports -1, "all", an upper-case name) alone: the group stores them canonically,
 * so a declared rule keyed on its raw spelling would be authorized again and refused as a duplicate.
 */
@QuarkusTest
class CloudFormationSecurityGroupAllTrafficUpdateIntegrationTest {

    @Test
    void aReExecutedGroupAndRuleKeepTheirAllTrafficRules() {
        String stackName = "cfn-sg-all-" + UUID.randomUUID();
        CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1);
        try {
            cfn.createStack(r -> r.stackName(stackName).templateBody(template("v1")));
            String groupId = CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE").outputs().getFirst()
                    .outputValue();
            Set<String> before = ruleIds(ec2, groupId);
            assertEquals(5, before.size(), "implicit egress, three inline ingress, one standalone ingress");

            // Only a tag and a rule description change: the group and the standalone rule are
            // re-executed, the rules they declare are not.
            cfn.updateStack(r -> r.stackName(stackName).templateBody(template("v2")));
            CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");

            assertEquals(before, ruleIds(ec2, groupId));
            assertEquals(Set.of("v2"), ec2.describeSecurityGroupRules(r -> r.filters(f -> f.name("group-id").values(groupId)))
                    .securityGroupRules().stream().filter(x -> "10.4.0.0/16".equals(x.cidrIpv4()))
                    .map(SecurityGroupRule::description).collect(Collectors.toSet()));
        } finally {
            cfn.deleteStack(r -> r.stackName(stackName));
            ec2.close();
            cfn.close();
        }
    }

    @Test
    void aFailedAuthorizeRollsBackWithoutRevokingRulesItNeverAdded() {
        String stackName = "cfn-sg-partial-" + UUID.randomUUID();
        CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1);
        try {
            cfn.createStack(r -> r.stackName(stackName).templateBody(partial("")));
            String groupId = CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE").outputs().getFirst()
                    .outputValue();
            Set<String> before = ruleIds(ec2, groupId);

            // The first added rule is valid and lands; its twin, spelled with the protocol number, is refused as a duplicate, so the rollback finds
            // one authorized rule present and one never added.
            cfn.updateStack(r -> r.stackName(stackName).templateBody(partial("""
                    ,
                    {"IpProtocol": "tcp", "FromPort": 81, "ToPort": 81, "CidrIp": "10.8.0.0/16"},
                    {"IpProtocol": "6", "FromPort": 81, "ToPort": 81, "CidrIp": "10.8.0.0/16"}""")));
            CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");

            assertEquals(before, ruleIds(ec2, groupId));
        } finally {
            cfn.deleteStack(r -> r.stackName(stackName));
            ec2.close();
            cfn.close();
        }
    }

    private static String partial(String more) {
        return """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.62.0.0/16"}},
                    "Group": {"Type": "AWS::EC2::SecurityGroup", "Properties": {
                      "GroupDescription": "partial", "VpcId": {"Ref": "Vpc"},
                      "SecurityGroupIngress": [
                        {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "CidrIp": "10.1.0.0/16"}%s
                      ]}}
                  },
                  "Outputs": {"GroupId": {"Value": {"Ref": "Group"}}}
                }
                """.formatted(more);
    }

    private static Set<String> ruleIds(Ec2Client ec2, String groupId) {
        return ec2.describeSecurityGroupRules(r -> r.filters(f -> f.name("group-id").values(groupId)))
                .securityGroupRules().stream().map(SecurityGroupRule::securityGroupRuleId)
                .collect(Collectors.toSet());
    }

    private static String template(String version) {
        return """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.61.0.0/16"}},
                    "Group": {"Type": "AWS::EC2::SecurityGroup", "Properties": {
                      "GroupDescription": "all traffic", "VpcId": {"Ref": "Vpc"},
                      "SecurityGroupIngress": [
                        {"IpProtocol": "-1", "FromPort": -1, "ToPort": -1, "CidrIp": "10.1.0.0/16"},
                        {"IpProtocol": "all", "CidrIp": "10.2.0.0/16"},
                        {"IpProtocol": "TCP", "FromPort": 22, "ToPort": 22, "CidrIp": "10.3.0.0/16"}
                      ],
                      "Tags": [{"Key": "version", "Value": "%1$s"}]}},
                    "Extra": {"Type": "AWS::EC2::SecurityGroupIngress", "Properties": {
                      "GroupId": {"Fn::GetAtt": ["Group", "GroupId"]}, "IpProtocol": "all", "CidrIp": "10.4.0.0/16",
                      "Description": "%1$s"}}
                  },
                  "Outputs": {"GroupId": {"Value": {"Ref": "Group"}}}
                }
                """.formatted(version);
    }
}
