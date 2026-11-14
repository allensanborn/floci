package io.floci.aws.services.ec2;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DryRun on the EBS encryption defaults, Transit Gateway, Client VPN, key pair, launch template and
 * describe calls is answered with DryRunOperation and HTTP 412 before the resource is resolved, so a
 * well-formed id that does not exist still gets it, and the request changes nothing.
 */
@QuarkusTest
class Ec2ControlPlaneDryRunIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(726200000000L);
    private static final String TGW = "tgw-0123456789abcdef0";
    private static final String ATTACHMENT = "tgw-attach-0123456789abcdef0";
    private static final String ROUTE_TABLE = "tgw-rtb-0123456789abcdef0";
    private static final String CLIENT_VPN = "cvpn-endpoint-0123456789abcdef0";

    private final String account = Long.toString(ACCOUNTS.incrementAndGet());

    /** Calls with nothing to resolve, and calls AWS answers before it looks the resource up. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GetEbsEncryptionByDefault|",
            "EnableEbsEncryptionByDefault|",
            "DisableEbsEncryptionByDefault|",
            "GetEbsDefaultKmsKeyId|",
            "ModifyEbsDefaultKmsKeyId|KmsKeyId=alias/aws/ebs",
            "ResetEbsDefaultKmsKeyId|",
            "DeleteKeyPair|KeyName=no-such-key",
            "DeleteFlowLogs|FlowLogId.1=fl-0123456789abcdef0"
    })
    void aDryRunIsAnsweredWithDryRunOperation(String action, String query) {
        dryRun(action, query).then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"))
                .body("Response.Errors.Error.Message",
                        equalTo("Request would have succeeded, but DryRun flag is set."));
    }

    /** A dry run the real call would refuse keeps the real call's error. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DeleteLaunchTemplate|LaunchTemplateName=no-such-template|InvalidLaunchTemplateName.NotFoundException",
            "ModifyEbsDefaultKmsKeyId||MissingParameter",
            "DeleteKeyPair||MissingParameter"
    })
    void aDryRunTheRealCallWouldRefuseKeepsItsError(String action, String query, String errorCode) {
        dryRun(action, query).then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));
        // The real call, without DryRun, is refused the same way.
        request(action).formParams(params(query)).post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));
    }

    /** A DryRun value that is not a boolean is refused as one. */
    @ParameterizedTest
    @CsvSource({
            "DeleteKeyPair, maybe",
            "DeleteLaunchTemplate, ''",
            "EnableEbsEncryptionByDefault, ''"
    })
    void aDryRunValueThatIsNotABooleanIsRefused(String action, String value) {
        request(action).formParam("DryRun", value)
                .formParam("TransitGatewayId", TGW).formParam("KeyName", "k")
                .formParam("LaunchTemplateName", "t").formParam("ClientVpnEndpointId", CLIENT_VPN)
                .post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("DryRun is not a valid boolean."));
    }

    @Test
    void aDryRunDisableLeavesEbsEncryptionByDefaultOn() {
        ec2("EnableEbsEncryptionByDefault");
        dryRun("DisableEbsEncryptionByDefault", null).then().statusCode(412);
        assertEquals("true", ec2("GetEbsEncryptionByDefault")
                .getString("GetEbsEncryptionByDefaultResponse.ebsEncryptionByDefault"));
    }

    @Test
    void aDryRunDeleteLeavesTheKeyPairAndTheLaunchTemplate() {
        ec2("CreateKeyPair", "KeyName", "dry-run-key");
        dryRun("DeleteKeyPair", "KeyName=dry-run-key").then().statusCode(412);
        assertEquals("dry-run-key", ec2("DescribeKeyPairs", "KeyName.1", "dry-run-key")
                .getString("DescribeKeyPairsResponse.keySet.item.keyName"));

        ec2("CreateLaunchTemplate", "LaunchTemplateName", "dry-run-template",
                "LaunchTemplateData.InstanceType", "t3.micro");
        dryRun("DeleteLaunchTemplate", "LaunchTemplateName=dry-run-template").then().statusCode(412);
        assertEquals("dry-run-template", ec2("DescribeLaunchTemplates", "LaunchTemplateName.1", "dry-run-template")
                .getString("DescribeLaunchTemplatesResponse.launchTemplates.item.launchTemplateName"));
    }

    private Response dryRun(String action, String query) {
        return request(action).formParams(params(query)).formParam("DryRun", "true").post("/");
    }

    private static Map<String, String> params(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }

    private XmlPath ec2(String action, String... params) {
        return request(action, params).post("/").then().statusCode(200).extract().xmlPath();
    }

    private RequestSpecification request(String action, String... params) {
        return QueryRequests.request(account, "ec2", action, params);
    }
}
