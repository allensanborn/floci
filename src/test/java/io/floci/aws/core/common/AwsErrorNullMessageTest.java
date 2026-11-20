package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An error thrown with no message reaches the wire differently by protocol: awsJson and
 * smithy-rpc-v2-cbor leave the member off (botocore then reads ''), rest-json writes
 * {@code "message":null}, which AWS sends there and which botocore reads as None.
 */
class AwsErrorNullMessageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final AwsException NO_MESSAGE = new AwsException("SomeException", null, 400);

    @Test
    void awsJsonOmitsANullMessage() throws Exception {
        Response response = JsonErrorResponseUtils.createErrorResponse(NO_MESSAGE);
        JsonNode body = JSON.readTree(JSON.writeValueAsBytes(response.getEntity()));
        assertEquals("SomeException", body.path("__type").asText());
        assertFalse(body.has("message"), body.toString());
    }

    @Test
    void awsJsonKeepsAMessage() throws Exception {
        Response response = JsonErrorResponseUtils.createErrorResponse(new AwsException("SomeException", "boom", 400));
        assertEquals("boom", JSON.readTree(JSON.writeValueAsBytes(response.getEntity())).path("message").asText());
    }

    @Test
    void cborOmitsANullMessage() throws Exception {
        Response response = CborErrorResponses.of(NO_MESSAGE, CborErrorResponses.GENERIC_CBOR_MEDIA_TYPE);
        JsonNode body = CBOR.readTree((byte[]) response.getEntity());
        assertEquals("SomeException", body.path("__type").asText());
        assertFalse(body.has("message"), body.toString());
    }

    @Test
    void restJsonKeepsANullMessage() throws Exception {
        Response response = new AwsExceptionMapper(JSON).toResponse(NO_MESSAGE);
        JsonNode body = JSON.readTree(JSON.writeValueAsBytes(response.getEntity()));
        assertTrue(body.has("message"), body.toString());
        assertTrue(body.get("message").isNull(), body.toString());
    }
}
