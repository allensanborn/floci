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
 * An update that touches only an unrelated resource must leave rules declared as all traffic
 * ({@code -1} with ports -1, "all", an upper-case name) alone: the group stores them canonically,
 * so a declared rule keyed on its raw spelling would be authorized again and refused as a duplicate.
 */
@QuarkusTest
class CloudFormationSecurityGroupAllTrafficUpdateIntegrationTest {

    @Test
    void anUnrelatedUpdateKeepsAllTrafficRulesAsTheyWere() {
        String stackName = "cfn-sg-all-" + UUID.randomUUID();
        CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1);
        try {
            cfn.createStack(r -> r.stackName(stackName).templateBody(template("")));
            String groupId = CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE").outputs().getFirst()
                    .outputValue();
            Set<String> before = ruleIds(ec2, groupId);
            assertEquals(5, before.size(), "implicit egress, three inline ingress, one standalone ingress");

            String queue = ",\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}";
            cfn.updateStack(r -> r.stackName(stackName).templateBody(template(queue)));
            CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");

            assertEquals(before, ruleIds(ec2, groupId));
        } finally {
            cfn.deleteStack(r -> r.stackName(stackName));
            ec2.close();
            cfn.close();
        }
    }

    private static Set<String> ruleIds(Ec2Client ec2, String groupId) {
        return ec2.describeSecurityGroupRules(r -> r.filters(f -> f.name("group-id").values(groupId)))
                .securityGroupRules().stream().map(SecurityGroupRule::securityGroupRuleId)
                .collect(Collectors.toSet());
    }

    private static String template(String more) {
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
                      ]}},
                    "Extra": {"Type": "AWS::EC2::SecurityGroupIngress", "Properties": {
                      "GroupId": {"Fn::GetAtt": ["Group", "GroupId"]}, "IpProtocol": "all", "CidrIp": "10.4.0.0/16"}}%s
                  },
                  "Outputs": {"GroupId": {"Value": {"Ref": "Group"}}}
                }
                """.formatted(more);
    }
}
