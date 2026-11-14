package io.floci.aws.core.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public record AwsErrorResponse(@JsonProperty("__type") String type, @JsonProperty("message") String message) {

    /**
     * The error body an awsJson or smithy-rpc-v2-cbor service writes. A null message is left off
     * rather than written as {@code null}: botocore reads an absent member as the empty string and
     * a null one as {@code None}, and these protocols send no message for an error thrown with none.
     * rest-json keeps {@code "message":null}, which AWS does send there, so it uses the record.
     */
    public static Map<String, String> omittingNullMessage(String type, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("__type", type);
        if (message != null) {
            body.put("message", message);
        }
        return body;
    }
}
