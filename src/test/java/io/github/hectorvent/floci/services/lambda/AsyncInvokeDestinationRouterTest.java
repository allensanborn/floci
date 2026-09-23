package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncInvokeDestinationRouterTest {

    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:bank-pawnshop";
    private static final String BUS_ARN = "arn:aws:events:us-east-1:000000000000:event-bus/quotes-bus";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:quotes-queue";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:quotes-topic";
    private static final String COLLECTOR_ARN = "arn:aws:lambda:us-east-1:000000000000:function:collector";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock LambdaService lambdaService;
    @Mock EventBridgeService eventBridgeService;
    @Mock SqsService sqsService;
    @Mock SnsService snsService;
    @Mock EmulatorConfig config;

    private AsyncInvokeDestinationRouter router;
    private LambdaFunction fn;

    @BeforeEach
    void setUp() {
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(eventBridgeService.putEvents(any(), any(), any()))
                .thenReturn(new EventBridgeService.PutEventsResult(0, List.of()));
        when(lambdaService.findEventInvokeConfig(any())).thenReturn(Optional.empty());

        router = new AsyncInvokeDestinationRouter(instanceOf(lambdaService), instanceOf(eventBridgeService),
                instanceOf(sqsService), instanceOf(snsService), MAPPER, config);

        fn = new LambdaFunction();
        fn.setFunctionName("bank-pawnshop");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("000000000000");
    }

    @Test
    void eventBridgeDestination_carriesTheFunctionAndDestinationAsEventResources() {
        // AWS fills the event's resources with the invoked function and the destination. Without
        // them a rule whose pattern matches on `resources` never fires: the field arrives empty
        // rather than absent, so the pattern does not match and the record is dropped at the bus.
        // Rules matching on `detail` are unaffected, which is why this stayed invisible.
        configure(BUS_ARN, null);

        router.route(fn, request(), success("{\"bankId\":\"PawnShop\"}"), 0);

        JsonNode resources = capturedEventEntry().get("Resources");
        assertTrue(resources != null && resources.isArray() && resources.size() == 2,
                "the entry must carry both ARNs as Resources, was: " + resources);
        assertEquals(FUNCTION_ARN, resources.get(0).asText());
        assertEquals(BUS_ARN, resources.get(1).asText());
    }

    @Test
    void onSuccessEventBridgeDestination_putsTheRecordOnTheBusAsTheEventDetail() {
        configure(BUS_ARN, null);

        router.route(fn, request(), success("{\"bankId\":\"PawnShop\",\"rate\":3.5}"), 0);

        JsonNode entry = capturedEventEntry();
        assertEquals("lambda", entry.get("Source").asText());
        assertEquals("Lambda Function Invocation Result - Success", entry.get("DetailType").asText());
        assertEquals(BUS_ARN, entry.get("EventBusName").asText());

        JsonNode detail = detailOf(entry);
        assertEquals("1.0", detail.get("version").asText());
        assertTrue(detail.hasNonNull("timestamp"), "record should carry a timestamp");
        assertEquals("Success", detail.path("requestContext").path("condition").asText());
        assertEquals(FUNCTION_ARN + ":$LATEST", detail.path("requestContext").path("functionArn").asText());
        assertEquals("req-1", detail.path("requestContext").path("requestId").asText());
        assertEquals(1, detail.path("requestContext").path("approximateInvokeCount").asInt());
        assertEquals("BANK-PawnShop", detail.path("requestPayload").path("bankId").asText());
        assertEquals(200, detail.path("responseContext").path("statusCode").asInt());
        assertEquals("$LATEST", detail.path("responseContext").path("executedVersion").asText());
        assertTrue(detail.path("responseContext").path("functionError").isMissingNode(),
                "a success record carries no functionError");
        // The rule of a real CDK application matches on this path.
        assertEquals("PawnShop", detail.path("responsePayload").path("bankId").asText());
        assertEquals(3.5, detail.path("responsePayload").path("rate").asDouble());
    }

    @Test
    void onSuccessSqsDestination_sendsTheRecordToTheQueue() {
        configure(QUEUE_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(eq("http://localhost:4566/000000000000/quotes-queue"),
                body.capture(), eq(0), eq("us-east-1"));

        JsonNode record = read(body.getValue());
        assertEquals("Success", record.path("requestContext").path("condition").asText());
        assertEquals(3.5, record.path("responsePayload").path("rate").asDouble());
        verifyNoInteractions(eventBridgeService);
    }

    @Test
    void onSuccessSnsDestination_publishesTheRecordToTheTopic() {
        configure(TOPIC_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(snsService).publish(eq(TOPIC_ARN), eq(null), message.capture(), anyString(), eq("us-east-1"));
        assertEquals(3.5, read(message.getValue()).path("responsePayload").path("rate").asDouble());
    }

    @Test
    void onSuccessLambdaDestination_invokesTheFunctionAsynchronously() {
        configure(COLLECTOR_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArnFromDestination(eq(COLLECTOR_ARN), payload.capture(), eq(1));
        assertEquals(3.5, read(new String(payload.getValue())).path("responsePayload").path("rate").asDouble());
    }

    @Test
    void lambdaDestinationPartWayAlongAChain_invokesWithTheNextHopCount() {
        configure(COLLECTOR_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 3);

        verify(lambdaService).invokeArnFromDestination(eq(COLLECTOR_ARN), any(), eq(4));
    }

    @Test
    void lambdaDestinationAtTheChainLimit_stopsRatherThanInvokingAgain() {
        configure(COLLECTOR_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 16);

        verify(lambdaService, never()).invokeArnFromDestination(anyString(), any(), anyInt());
    }

    @Test
    void nonLambdaDestinationAtTheChainLimit_isStillDelivered() {
        // The bound exists to stop a chain re-entering Lambda; a queue is the end of one.
        configure(QUEUE_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 16);

        verify(sqsService).sendMessage(eq("http://localhost:4566/000000000000/quotes-queue"),
                anyString(), eq(0), eq("us-east-1"));
    }

    @Test
    void handlerError_routesToTheFailureDestinationWithTheErrorPayload() {
        configure(BUS_ARN, BUS_ARN);
        InvokeResult failure = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"boom\",\"errorType\":\"Error\"}".getBytes(), null, "req-1");

        router.route(fn, request(), failure, 0);

        JsonNode entry = capturedEventEntry();
        assertEquals("Lambda Function Invocation Result - Failure", entry.get("DetailType").asText());

        JsonNode detail = detailOf(entry);
        assertEquals("RetriesExhausted", detail.path("requestContext").path("condition").asText());
        assertEquals("Unhandled", detail.path("responseContext").path("functionError").asText());
        assertEquals("boom", detail.path("responsePayload").path("errorMessage").asText());
        assertEquals("Error", detail.path("responsePayload").path("errorType").asText());
    }

    @Test
    void handlerErrorWithNoFailureDestination_deliversNothing() {
        configure(BUS_ARN, null);
        InvokeResult failure = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"boom\"}".getBytes(), null, "req-1");

        router.route(fn, request(), failure, 0);

        verifyNoInteractions(eventBridgeService, sqsService, snsService);
    }

    @Test
    void noEventInvokeConfig_deliversNothing() {
        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        verifyNoInteractions(eventBridgeService, sqsService, snsService);
        verify(lambdaService).findEventInvokeConfig(fn);
    }

    @Test
    void eventInvokeConfigWithoutDestinations_deliversNothing() {
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(0);
        when(lambdaService.findEventInvokeConfig(fn)).thenReturn(Optional.of(config));

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        verifyNoInteractions(eventBridgeService, sqsService, snsService);
    }

    @Test
    void unsupportedDestinationArn_deliversNothing() {
        configure("arn:aws:states:us-east-1:000000000000:stateMachine:quotes", null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        verifyNoInteractions(eventBridgeService, sqsService, snsService);
    }

    @Test
    void destinationThatRejectsTheRecord_doesNotReachTheCaller() {
        configure(QUEUE_ARN, null);
        when(sqsService.sendMessage(anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("The specified queue does not exist."));

        assertDoesNotThrow(() -> router.route(fn, request(), success("{\"rate\":3.5}"), 0));
    }

    @Test
    void publishedVersion_recordsTheExecutedVersion() {
        fn.setFunctionArn(FUNCTION_ARN + ":3");
        fn.setVersion("3");
        configure(BUS_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        JsonNode detail = detailOf(capturedEventEntry());
        assertEquals(FUNCTION_ARN + ":3", detail.path("requestContext").path("functionArn").asText());
        assertEquals("3", detail.path("responseContext").path("executedVersion").asText());
    }

    private void configure(String onSuccess, String onFailure) {
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        FunctionEventInvokeConfig.DestinationConfig destinations =
                new FunctionEventInvokeConfig.DestinationConfig();
        if (onSuccess != null) {
            destinations.setOnSuccess(new FunctionEventInvokeConfig.Destination(onSuccess));
        }
        if (onFailure != null) {
            destinations.setOnFailure(new FunctionEventInvokeConfig.Destination(onFailure));
        }
        config.setDestinationConfig(destinations);
        when(lambdaService.findEventInvokeConfig(fn)).thenReturn(Optional.of(config));
    }

    private JsonNode capturedEventEntry() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> entries = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(entries.capture(), eq("us-east-1"), eq(null));
        assertEquals(1, entries.getValue().size(), "one entry per delivery");
        return MAPPER.valueToTree(entries.getValue().get(0));
    }

    private static JsonNode detailOf(JsonNode entry) {
        return read(entry.get("Detail").asText());
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("destination record is not JSON: " + json, e);
        }
    }

    private static byte[] request() {
        return "{\"bankId\":\"BANK-PawnShop\",\"amount\":100000}".getBytes();
    }

    private static InvokeResult success(String payload) {
        return new InvokeResult(200, null, payload.getBytes(), null, "req-1");
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instanceOf(T bean) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(bean);
        return instance;
    }
}
