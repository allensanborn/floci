package io.floci.aws.services.acm;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** The shipped default, {@code validation-wait-seconds: -1}: a requested public certificate stays pending. */
@QuarkusTest
@TestProfile(AcmPendingValidationIntegrationTest.PendingProfile.class)
class AcmPendingValidationIntegrationTest {

    public static class PendingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.acm.validation-wait-seconds", "-1");
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse acm(String action, String body) {
        return given()
            .header("X-Amz-Target", "CertificateManager." + action)
            .contentType("application/x-amz-json-1.1")
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private static String request(String domain, String extra) {
        return acm("RequestCertificate", "{\"DomainName\":\"" + domain + "\"" + extra + "}")
            .statusCode(200).extract().jsonPath().getString("CertificateArn");
    }

    @Test
    void aRequestedCertificateIsPendingAndHasSignedNothing() {
        String arn = request("pending-it.example.com", ",\"ValidationMethod\":\"DNS\"");

        acm("DescribeCertificate", "{\"CertificateArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("Certificate.Status", equalTo("PENDING_VALIDATION"))
            .body("Certificate.Type", equalTo("AMAZON_ISSUED"))
            .body("Certificate.Serial", nullValue())
            .body("Certificate.NotBefore", nullValue())
            .body("Certificate.NotAfter", nullValue())
            .body("Certificate.Options.CertificateTransparencyLoggingPreference", equalTo("ENABLED"))
            .body("Certificate.DomainValidationOptions[0].ValidationStatus", equalTo("PENDING_VALIDATION"))
            .body("Certificate.DomainValidationOptions[0].ResourceRecord.Type", equalTo("CNAME"));

        acm("ListCertificates", "{\"CertificateStatuses\":[\"PENDING_VALIDATION\"]}")
            .statusCode(200).body("CertificateSummaryList.CertificateArn", hasItem(arn));
        acm("ListCertificates", "{\"CertificateStatuses\":[\"ISSUED\"]}")
            .statusCode(200).body("CertificateSummaryList.CertificateArn", not(hasItem(arn)));
    }

    @Test
    void getCertificateOnAPendingCertificateIsInProgress() {
        String arn = request("get-pending-it.example.com", "");
        String account = arn.split(":")[4];

        acm("GetCertificate", "{\"CertificateArn\":\"" + arn + "\"}")
            .statusCode(400)
            .body("__type", equalTo("RequestInProgressException"))
            .body("message", equalTo("Certificate " + arn + " in account " + account + " not yet issued"));
    }

    @Test
    void updateCertificateOptionsOnAPendingCertificateIsInvalidState() {
        String arn = request("update-pending-it.example.com", ",\"ValidationMethod\":\"EMAIL\"");

        acm("UpdateCertificateOptions", "{\"CertificateArn\":\"" + arn
                + "\",\"Options\":{\"CertificateTransparencyLoggingPreference\":\"DISABLED\"}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidStateException"))
            .body("message", equalTo("The selected certificate is not Issued and/or Valid."));
        acm("UpdateCertificateOptions", "{\"CertificateArn\":\"" + arn + "\",\"Options\":{\"ValidationMethod\":\"DNS\"}}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Certificate is not issued"));
        acm("DescribeCertificate", "{\"CertificateArn\":\"" + arn + "\"}")
            .body("Certificate.Options.CertificateTransparencyLoggingPreference", equalTo("ENABLED"))
            .body("Certificate.DomainValidationOptions[0].ValidationMethod", equalTo("EMAIL"));
    }

    @Test
    void listCertificateDomainValidationsReportsEachNameAndPages() {
        String arn = request("lcdv-it.example.com", ",\"SubjectAlternativeNames\":[\"*.lcdv-it.example.com\",\"www.lcdv-it.example.com\"]");

        acm("ListCertificateDomainValidations", "{\"CertificateArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("DomainValidationSummaryList.DomainName", containsInAnyOrder(
                "lcdv-it.example.com", "*.lcdv-it.example.com", "www.lcdv-it.example.com"))
            .body("DomainValidationSummaryList[0].ActiveValidationConfiguration.ValidationStatus",
                equalTo("PENDING_VALIDATION"))
            .body("DomainValidationSummaryList[0].ActiveValidationConfiguration.ValidationChallenge"
                + ".DnsValidationChallenge.ResourceRecord.Type", equalTo("CNAME"));

        String token = acm("ListCertificateDomainValidations", "{\"CertificateArn\":\"" + arn + "\",\"MaxItems\":2}")
            .statusCode(200).extract().jsonPath().getString("NextToken");
        acm("ListCertificateDomainValidations", "{\"CertificateArn\":\"" + arn + "\",\"NextToken\":\"" + token + "\"}")
            .statusCode(200).body("DomainValidationSummaryList.size()", equalTo(1))
            .body("NextToken", nullValue());
    }

    @Test
    void searchCertificatesFiltersByArnAndStatusAndSeesDeletion() {
        String arn = request("search-it.example.com", ",\"ValidationMethod\":\"DNS\"");

        acm("SearchCertificates", "{\"FilterStatement\":{\"Filter\":{\"CertificateArn\":\"" + arn + "\"}}}")
            .statusCode(200)
            .body("Results.size()", equalTo(1))
            .body("Results[0].CertificateMetadata.AcmCertificateMetadata.Status", equalTo("PENDING_VALIDATION"))
            .body("Results[0].CertificateMetadata.AcmCertificateMetadata.Type", equalTo("AMAZON_ISSUED"))
            .body("Results[0].CertificateMetadata.AcmCertificateMetadata.ValidationMethod", equalTo("DNS"));
        acm("SearchCertificates", "{\"FilterStatement\":{\"And\":[{\"Filter\":{\"CertificateArn\":\"" + arn
                + "\"}},{\"Filter\":{\"AcmCertificateMetadataFilter\":{\"Status\":\"ISSUED\"}}}]}}")
            .statusCode(200).body("Results.size()", equalTo(0));

        acm("DeleteCertificate", "{\"CertificateArn\":\"" + arn + "\"}").statusCode(200);
        acm("SearchCertificates", "{\"FilterStatement\":{\"Filter\":{\"CertificateArn\":\"" + arn + "\"}}}")
            .statusCode(200).body("Results.size()", equalTo(0));
        acm("DescribeCertificate", "{\"CertificateArn\":\"" + arn + "\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("Could not find certificate " + arn + "."));
    }

    @Test
    void searchCertificatesRefusesAFilterItDoesNotUnderstand() {
        acm("SearchCertificates", "{\"FilterStatement\":{\"Filter\":{\"X509AttributeFilter\":{}}}}")
            .statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void listCertificateDomainValidationsRefusesAnUnknownToken() {
        String arn = request("token-it.example.com", "");

        acm("ListCertificateDomainValidations", "{\"CertificateArn\":\"" + arn + "\",\"NextToken\":\"nope\"}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Invalid next token."));
    }

    @Test
    void searchCertificatesRefusesAUnionThatIsNotExactlyOneMember() {
        acm("SearchCertificates", "{\"FilterStatement\":{\"And\":[],\"Or\":[]}}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Each CertificateFilterStatement must contain exactly one member. "
                + "A CertificateFilterStatement in your request contains the members [And, Or]."));
        acm("SearchCertificates", "{\"FilterStatement\":{}}")
            .statusCode(400).body("__type", equalTo("ValidationException"));
        acm("SearchCertificates", "{\"FilterStatement\":{\"And\":[]}}")
            .statusCode(400).body("__type", equalTo("ValidationException"));
        acm("SearchCertificates", "{\"FilterStatement\":{\"Filter\":{\"CertificateArn\":\"a\","
                + "\"AcmCertificateMetadataFilter\":{\"Status\":\"ISSUED\"}}}}")
            .statusCode(400).body("message", equalTo("Each CertificateFilter must contain exactly one member. "
                + "A CertificateFilter in your request contains the members [CertificateArn, AcmCertificateMetadataFilter]."));
        acm("SearchCertificates", "{\"FilterStatement\":{\"Filter\":{\"AcmCertificateMetadataFilter\":"
                + "{\"Status\":\"ISSUED\",\"Type\":\"IMPORTED\"}}}}")
            .statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void searchCertificatesBoundsMaxResults() {
        for (String bad : new String[] {"0", "-1", "501"}) {
            acm("SearchCertificates", "{\"MaxResults\":" + bad + "}")
                .statusCode(400).body("__type", equalTo("ValidationException"));
        }
        acm("SearchCertificates", "{\"MaxResults\":500}").statusCode(200);
    }
}
