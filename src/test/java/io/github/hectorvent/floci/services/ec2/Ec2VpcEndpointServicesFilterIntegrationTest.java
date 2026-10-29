package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** DescribeVpcEndpointServices honours Filter.N, over both ServiceNames and ServiceDetails. */
@QuarkusTest
class Ec2VpcEndpointServicesFilterIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ROOT = "DescribeVpcEndpointServicesResponse.";

    private static XmlPath describe(String... params) {
        RequestSpecification req = given().formParam("Action", "DescribeVpcEndpointServices")
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < params.length; i += 2) {
            req = req.formParam(params[i], params[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private static void assertNames(XmlPath xml, String... expected) {
        List<String> names = xml.getList(ROOT + "serviceNameSet.item", String.class);
        List<String> details = xml.getList(ROOT + "serviceDetailSet.item.serviceName", String.class);
        assertEquals(List.of(expected), names);
        assertEquals(names, details);
    }

    @Test
    void serviceTypeGatewayKeepsOnlyS3() {
        assertNames(describe("Filter.1.Name", "service-type", "Filter.1.Value.1", "Gateway"),
                "com.amazonaws.us-east-1.s3");
    }

    @Test
    void serviceTypeInterfaceKeepsS3ButNotAGatewayOnlyService() {
        XmlPath xml = describe("Filter.1.Name", "service-type", "Filter.1.Value.1", "Interface");
        List<String> names = xml.getList(ROOT + "serviceNameSet.item", String.class);
        assertEquals(true, names.contains("com.amazonaws.us-east-1.s3"));
        assertEquals(true, names.contains("com.amazonaws.us-east-1.sqs"));
        assertEquals(names, xml.getList(ROOT + "serviceDetailSet.item.serviceName", String.class));
    }

    @Test
    void serviceNameFilterReturnsExactlyThatService() {
        assertNames(describe("Filter.1.Name", "service-name",
                "Filter.1.Value.1", "com.amazonaws.us-east-1.sqs"),
                "com.amazonaws.us-east-1.sqs");
    }

    @Test
    void serviceNamesAndNonMatchingFilterAreAnded() {
        assertNames(describe("ServiceName.1", "com.amazonaws.us-east-1.sqs",
                "Filter.1.Name", "service-type", "Filter.1.Value.1", "Gateway"));
    }

    @Test
    void serviceNamesAndMatchingFilterReturnTheService() {
        assertNames(describe("ServiceName.1", "com.amazonaws.us-east-1.sqs",
                "Filter.1.Name", "service-type", "Filter.1.Value.1", "Interface"),
                "com.amazonaws.us-east-1.sqs");
    }

    @Test
    void tagKeyFilterMatchesNothingBecauseServicesCarryNoTags() {
        assertNames(describe("Filter.1.Name", "tag-key", "Filter.1.Value.1", "Name"));
    }
}
