package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** How security group rules read back after authorize, revoke and description updates. */
@QuarkusTest
class SecurityGroupRuleShapeIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String PEER = "10.9.0.0/16";

    private static RequestSpecification call(String action) {
        return given().formParam("Action", action).header("Authorization", AUTH_HEADER);
    }

    /** A group in its own VPC: only the default VPC forgives a revoke of a rule the group lacks. */
    private static String newGroup(int vpcOctet) {
        String vpcId = call("CreateVpc").formParam("CidrBlock", "10." + vpcOctet + ".0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        return call("CreateSecurityGroup").formParam("GroupName", "sg-" + UUID.randomUUID())
                .formParam("GroupDescription", "rules").formParam("VpcId", vpcId)
                .post("/").then().statusCode(200).extract().path("CreateSecurityGroupResponse.groupId");
    }

    private static RequestSpecification permission(RequestSpecification r, String protocol, String ports, String cidr) {
        r = r.formParam("IpPermissions.1.IpProtocol", protocol);
        if (ports != null) {
            r = r.formParam("IpPermissions.1.FromPort", ports).formParam("IpPermissions.1.ToPort", ports);
        }
        return r.formParam("IpPermissions.1.IpRanges.1.CidrIp", cidr);
    }

    private static XmlPath rules(String groupId) {
        return call("DescribeSecurityGroupRules").formParam("Filter.1.Name", "group-id")
                .formParam("Filter.1.Value.1", groupId).post("/").then().statusCode(200).extract().xmlPath();
    }

    @Test
    void protocolKeywordsAreCaseInsensitiveAliases() {
        String groupId = newGroup(201);

        XmlPath granted = permission(call("AuthorizeSecurityGroupIngress").formParam("GroupId", groupId), "all", "0", PEER)
                .post("/").then().statusCode(200).extract().xmlPath();
        assertThat(granted.getString("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.ipProtocol"), equalTo("-1"));
        assertThat(granted.getString("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.fromPort"), equalTo("-1"));
        assertThat(granted.getString("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.toPort"), equalTo("-1"));

        for (String alias : List.of("ALL", "-1")) {
            permission(call("AuthorizeSecurityGroupIngress").formParam("GroupId", groupId), alias, "0", PEER)
                    .post("/").then().statusCode(400)
                    .body(containsString("InvalidPermission.Duplicate"))
                    .body(containsString("peer: " + PEER + ", ALL, ALLOW"));
        }

        XmlPath tcp = permission(call("AuthorizeSecurityGroupIngress").formParam("GroupId", groupId), "TCP", "0", PEER)
                .post("/").then().statusCode(200).extract().xmlPath();
        assertThat(tcp.getString("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.ipProtocol"), equalTo("tcp"));
        permission(call("AuthorizeSecurityGroupIngress").formParam("GroupId", groupId), "Tcp", "0", PEER)
                .post("/").then().statusCode(400)
                .body(containsString("peer: " + PEER + ", TCP, from port: 0, to port: 0, ALLOW"));

        // The group's own view of the same rules: all traffic carries no ports.
        XmlPath group = call("DescribeSecurityGroups").formParam("GroupId.1", groupId)
                .post("/").then().statusCode(200).extract().xmlPath();
        List<String> protocols = group.getList("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item.ipProtocol");
        assertThat(protocols, equalTo(List.of("-1", "tcp")));
        assertThat(group.getString("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item[0].fromPort"), equalTo(""));
    }

    @Test
    void implicitEgressRuleReadsBackAsAllTrafficWithPortsMinusOne() {
        String groupId = newGroup(202);

        XmlPath rules = rules(groupId);
        assertThat(rules.getList("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item"), hasSize(1));
        assertThat(rules.getString("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.ipProtocol"), equalTo("-1"));
        assertThat(rules.getString("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.fromPort"), equalTo("-1"));
        assertThat(rules.getString("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.toPort"), equalTo("-1"));
        XmlPath group = call("DescribeSecurityGroups").formParam("GroupId.1", groupId)
                .post("/").then().statusCode(200).extract().xmlPath();
        assertThat(group.getString("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissionsEgress.item.fromPort"), equalTo(""));
    }

    @Test
    void allTrafficEgressRevokeIgnoresPortsAndNamesTheRevokedRule() {
        String groupId = newGroup(203);
        String ruleId = rules(groupId).getString("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.securityGroupRuleId");

        XmlPath revoked = permission(call("RevokeSecurityGroupEgress").formParam("GroupId", groupId), "-1", "22", "0.0.0.0/0")
                .post("/").then().statusCode(200).extract().xmlPath();
        assertThat(revoked.getString("RevokeSecurityGroupEgressResponse.return"), equalTo("true"));
        assertThat(revoked.getList("RevokeSecurityGroupEgressResponse.revokedSecurityGroupRuleSet.item.securityGroupRuleId"),
                equalTo(List.of(ruleId)));
        // Read back through a separate call: the rule record is gone, not just the group's permission.
        assertThat(rules(groupId).getList("DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item"), empty());

        permission(call("RevokeSecurityGroupEgress").formParam("GroupId", groupId), "-1", "22", "0.0.0.0/0")
                .post("/").then().statusCode(400).body(containsString("InvalidPermission.NotFound"));
    }

    @Test
    void updatingEgressRuleDescriptionsChangesWhatIsReadBack() {
        String groupId = newGroup(204);
        permission(call("AuthorizeSecurityGroupEgress").formParam("GroupId", groupId), "tcp", "443", "10.0.0.0/8")
                .formParam("IpPermissions.1.IpRanges.1.Description", "initial")
                .post("/").then().statusCode(200);

        permission(call("UpdateSecurityGroupRuleDescriptionsEgress").formParam("GroupId", groupId), "tcp", "443", "10.0.0.0/8")
                .formParam("IpPermissions.1.IpRanges.1.Description", "updated")
                .post("/").then().statusCode(200);

        assertThat(rules(groupId).getString(
                "DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.find { it.cidrIpv4 == '10.0.0.0/8' }.description"),
                equalTo("updated"));
        XmlPath group = call("DescribeSecurityGroups").formParam("GroupId.1", groupId)
                .post("/").then().statusCode(200).extract().xmlPath();
        assertThat(group.getString("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissionsEgress.item"
                + ".find { it.ipProtocol == 'tcp' }.ipRanges.item.description"), equalTo("updated"));
    }
}
