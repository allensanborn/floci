package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact code, status and message of the CloudFront refusals whose wording AWS fixes. Each assertion
 * reads the error body from the wire, so a message that drifts back to the old text fails here.
 */
@QuarkusTest
class CloudFrontErrorMessagesIntegrationTest {

    private static final String API = "/2020-05-31/";
    private static final String NS = "xmlns=\"http://cloudfront.amazonaws.com/doc/2020-05-31/\"";
    private static final String MANAGED_CACHE_POLICY = "658327ea-f89d-4fab-a63d-7e88639e58f6";
    private static final String MANAGED_ORIGIN_REQUEST_POLICY = "216adef6-5c7f-47e4-b989-5492eafa07d3";
    private static final String MANAGED_RESPONSE_HEADERS_POLICY = "e61eb60c-9c35-4d20-a928-2b84e02af89c";

    private static void assertError(Response r, int status, String code, String message) {
        String xml = r.asString();
        assertEquals(status, r.statusCode(), xml);
        assertEquals(code, XmlParser.extractFirst(xml, "Code", null), xml);
        assertEquals(message, XmlParser.extractFirst(xml, "Message", ""), xml);
    }

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    private static String id(Response created) {
        return XmlParser.extractFirst(created.asString(), "Id", null);
    }

    private static Response post(String path, String body) {
        return given().contentType("application/xml").body(body).post(API + path);
    }

    private static Response put(String path, String etag, String body) {
        return given().contentType("application/xml").header("If-Match", etag).body(body).put(API + path);
    }

    private static Response delete(String path, String etag) {
        return given().header("If-Match", etag).delete(API + path);
    }

    private static String publicKeyPem() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        byte[] der = gen.generateKeyPair().getPublic().getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static String publicKeyBody(String callerRef, String name, String pem) {
        return "<PublicKeyConfig " + NS + "><CallerReference>" + callerRef + "</CallerReference><Name>"
                + name + "</Name><EncodedKey>" + pem + "</EncodedKey></PublicKeyConfig>";
    }

    private static String keyGroupBody(String name, String publicKeyId) {
        return "<KeyGroupConfig " + NS + "><Name>" + name + "</Name><Items><PublicKey>" + publicKeyId
                + "</PublicKey></Items></KeyGroupConfig>";
    }

    private static String distributionBody(String originExtras, String behaviorExtras) {
        return "<DistributionConfig " + NS + "><CallerReference>" + uid() + "</CallerReference>"
                + "<Enabled>false</Enabled><Origins><Quantity>1</Quantity><Items><Origin>"
                + "<Id>origin</Id><DomainName>bucket.s3.amazonaws.com</DomainName>" + originExtras
                + "</Origin></Items></Origins><DefaultCacheBehavior><TargetOriginId>origin</TargetOriginId>"
                + "<ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>" + behaviorExtras
                + "</DefaultCacheBehavior></DistributionConfig>";
    }

    @Test
    void publicKeyFamily() throws Exception {
        String pem = publicKeyPem();
        String ref = uid();
        Response created = post("public-key", publicKeyBody(ref, "pk-" + uid(), pem));
        assertEquals(201, created.statusCode(), created.asString());
        String keyId = id(created);
        String etag = created.header("ETag");

        assertError(post("public-key", publicKeyBody(ref, "pk-" + uid(), pem)), 409, "PublicKeyAlreadyExists",
                "The caller reference that you are using to create a CloudFront public key is associated "
                        + "with another public key in your account.");
        assertError(given().get(API + "public-key/KABCDEFGHIJKLMN"), 404, "NoSuchPublicKey",
                "The specified CloudFront public key does not exist.");
        assertError(put("public-key/" + keyId + "/config", etag, publicKeyBody(ref, "renamed-" + uid(), pem)),
                400, "CannotChangeImmutablePublicKeyFields",
                "You cannot modify encoded material and name of a public key once created.");

        Response group = post("key-group", keyGroupBody("kg-" + uid(), keyId));
        assertEquals(201, group.statusCode(), group.asString());
        assertError(delete("public-key/" + keyId, etag), 409, "PublicKeyInUse",
                "The Cloudfront public key is currently associated with either Key Group or a field level "
                        + "encryption profile. Please disassociate the key before deleting.");
        assertError(post("key-group", keyGroupBody("kg-" + uid(), "KABCDEFGHIJKLMN")), 400, "InvalidArgument",
                "The specified CloudFront public key does not exist in key group.");
    }

    @Test
    void keyGroupFamily() throws Exception {
        Response key = post("public-key", publicKeyBody(uid(), "pk-" + uid(), publicKeyPem()));
        String keyId = id(key);
        String name = "kg-" + uid();
        Response group = post("key-group", keyGroupBody(name, keyId));
        assertEquals(201, group.statusCode(), group.asString());

        String dup = "Another key group with the same name already exists within the aws account.";
        assertError(post("key-group", keyGroupBody(name, keyId)), 409, "KeyGroupAlreadyExists", dup);

        Response other = post("key-group", keyGroupBody("kg-" + uid(), keyId));
        assertError(put("key-group/" + id(other), other.header("ETag"), keyGroupBody(name, keyId)),
                409, "KeyGroupAlreadyExists", dup);

        assertError(given().get(API + "key-group/KGBOGUS0000000000"), 404, "NoSuchResource",
                "Id format is invalid");
        assertError(given().get(API + "key-group/" + keyId), 404, "NoSuchResource", "Id format is invalid");
        assertError(given().get(API + "key-group/" + uid()), 404, "NoSuchResource",
                "The specified key group does not exist.");
    }

    @Test
    void originAccessIdentityFamily() {
        String ref = uid();
        String body = "<CloudFrontOriginAccessIdentityConfig " + NS + "><CallerReference>" + ref
                + "</CallerReference><Comment>c</Comment></CloudFrontOriginAccessIdentityConfig>";
        Response created = post("origin-access-identity/cloudfront", body);
        assertEquals(201, created.statusCode(), created.asString());
        String oaiId = id(created);

        assertError(post("origin-access-identity/cloudfront", body), 409,
                "CloudFrontOriginAccessIdentityAlreadyExists",
                "The caller reference you are using to create the CloudFront origin access identity "
                        + "is associated with another identity.");
        assertError(given().get(API + "origin-access-identity/cloudfront/EABCDEFGHIJKLM"), 404,
                "NoSuchCloudFrontOriginAccessIdentity",
                "The specified CloudFront origin access identity does not exist.");

        Response dist = post("distribution", distributionBody("<S3OriginConfig><OriginAccessIdentity>"
                + "origin-access-identity/cloudfront/" + oaiId + "</OriginAccessIdentity></S3OriginConfig>", ""));
        assertEquals(201, dist.statusCode(), dist.asString());
        assertError(delete("origin-access-identity/cloudfront/" + oaiId, created.header("ETag")), 409,
                "CloudFrontOriginAccessIdentityInUse",
                "The CloudFront origin access identity is still being used.");
    }

    @Test
    void originAccessControlInUse() {
        Response created = post("origin-access-control", "<OriginAccessControlConfig " + NS + "><Name>oac-"
                + uid().substring(0, 8) + "</Name><SigningProtocol>sigv4</SigningProtocol>"
                + "<SigningBehavior>always</SigningBehavior><OriginAccessControlOriginType>s3"
                + "</OriginAccessControlOriginType></OriginAccessControlConfig>");
        assertEquals(201, created.statusCode(), created.asString());
        String oacId = id(created);
        Response dist = post("distribution", distributionBody("<S3OriginConfig><OriginAccessIdentity/>"
                + "</S3OriginConfig><OriginAccessControlId>" + oacId + "</OriginAccessControlId>", ""));
        assertEquals(201, dist.statusCode(), dist.asString());
        assertError(delete("origin-access-control/" + oacId, created.header("ETag")), 409,
                "OriginAccessControlInUse", "The CloudFront origin access control is still being used.");
    }

    @Test
    void managedPolicies() {
        String upd = "The request failed because update is not allowed for this policy.";
        String del = "The request failed because delete is not allowed for this policy.";
        assertError(delete("cache-policy/" + MANAGED_CACHE_POLICY, "x"), 400, "IllegalDelete", del);
        assertError(delete("origin-request-policy/" + MANAGED_ORIGIN_REQUEST_POLICY, "x"), 400,
                "IllegalDelete", del);
        assertError(delete("response-headers-policy/" + MANAGED_RESPONSE_HEADERS_POLICY, "x"), 400,
                "IllegalDelete", del);
        assertError(put("cache-policy/" + MANAGED_CACHE_POLICY, "x", "<CachePolicyConfig " + NS + "/>"), 400,
                "IllegalUpdate", upd);
        assertError(put("origin-request-policy/" + MANAGED_ORIGIN_REQUEST_POLICY, "x",
                "<OriginRequestPolicyConfig " + NS + "/>"), 400, "IllegalUpdate", upd);
    }

    @Test
    void responseHeadersPolicyFamily() {
        String name = "rhp-" + uid();
        Response created = post("response-headers-policy", rhpBody(name, "default-src 'self'"));
        assertEquals(201, created.statusCode(), created.asString());

        assertError(post("response-headers-policy", rhpBody(name, "default-src 'self'")), 409,
                "ResponseHeadersPolicyAlreadyExists",
                "Another response headers policy with the same name already exists within the aws account.");
        assertError(post("response-headers-policy", rhpBody("rhp-" + uid(), "a".repeat(2000))), 400,
                "TooLongCSPInResponseHeadersPolicy",
                "Your request contains longer Content Security Policy value in the Response Headers Policy "
                        + "than is allowed.");
        assertError(given().get(API + "response-headers-policy/NOSUCHPOLICYID000000"), 404,
                "NoSuchResponseHeadersPolicy", "Id format is invalid");
        assertError(given().get(API + "response-headers-policy/" + uid() + "/config"), 404,
                "NoSuchResponseHeadersPolicy", "The specified response headers policy does not exist.");
    }

    private static String rhpBody(String name, String csp) {
        return "<ResponseHeadersPolicyConfig " + NS + "><Name>" + name + "</Name><SecurityHeadersConfig>"
                + "<ContentSecurityPolicy><Override>true</Override><ContentSecurityPolicy>" + csp
                + "</ContentSecurityPolicy></ContentSecurityPolicy></SecurityHeadersConfig>"
                + "</ResponseHeadersPolicyConfig>";
    }

    @Test
    void functionAlreadyExistsCarriesNoMessage() {
        String name = "fn-" + uid().substring(0, 8);
        String body = "<CreateFunctionRequest " + NS + "><Name>" + name + "</Name><FunctionConfig>"
                + "<Comment>c</Comment><Runtime>cloudfront-js-2.0</Runtime></FunctionConfig><FunctionCode>"
                + Base64.getEncoder().encodeToString(
                        "function handler(e) { return e.request; }".getBytes(StandardCharsets.UTF_8))
                + "</FunctionCode></CreateFunctionRequest>";
        assertEquals(201, post("function", body).statusCode());
        assertError(post("function", body), 409, "FunctionAlreadyExists", "");
    }

    @Test
    void fieldLevelEncryptionAndMonitoringNotFound() throws Exception {
        Response key = post("public-key", publicKeyBody(uid(), "pk-" + uid(), publicKeyPem()));
        assertError(given().get(API + "field-level-encryption-profile/" + id(key)), 404,
                "NoSuchFieldLevelEncryptionProfile", "Id format is invalid");
        assertError(given().get(API + "field-level-encryption-profile/" + uid()), 404,
                "NoSuchFieldLevelEncryptionProfile",
                "The specified field level encryption profile does not exist.");
        assertError(given().get(API + "field-level-encryption/" + uid() + "/config"), 404,
                "NoSuchFieldLevelEncryptionConfig",
                "The specified field level encryption config does not exist.");

        Response dist = post("distribution", distributionBody("<CustomOriginConfig><HTTPPort>80</HTTPPort>"
                + "<HTTPSPort>443</HTTPSPort><OriginProtocolPolicy>http-only</OriginProtocolPolicy>"
                + "</CustomOriginConfig>", ""));
        assertEquals(201, dist.statusCode(), dist.asString());
        assertError(given().get(API + "distributions/" + id(dist) + "/monitoring-subscription"), 404,
                "NoSuchMonitoringSubscription", "The monitoring subscription does not exist.");
    }

    @Test
    void trustedKeyGroupValidation() {
        String ghost = uid();
        String custom = "<CustomOriginConfig><HTTPPort>80</HTTPPort><HTTPSPort>443</HTTPSPort>"
                + "<OriginProtocolPolicy>http-only</OriginProtocolPolicy></CustomOriginConfig>";
        assertError(post("distribution", distributionBody(custom, tkg(true, 1, ghost))), 400,
                "TrustedKeyGroupDoesNotExist", "One or more of your trusted keygroups do not exist.");
        assertError(post("distribution", distributionBody(custom, tkg(true, 2, ghost))), 400,
                "InconsistentQuantities",
                "The specified quantity of TrustedKeyGroups (2) does not match the actual quantity supplied (1).");
        assertError(post("distribution", distributionBody(custom, tkg(true, 0, null))), 400, "InvalidArgument",
                "The parameter TrustedKeyGroups must contain items if enabled.");
    }

    private static String tkg(boolean enabled, int quantity, String item) {
        return "<TrustedKeyGroups><Enabled>" + enabled + "</Enabled><Quantity>" + quantity + "</Quantity>"
                + (item == null ? "" : "<Items><KeyGroup>" + item + "</KeyGroup></Items>") + "</TrustedKeyGroups>";
    }
}
