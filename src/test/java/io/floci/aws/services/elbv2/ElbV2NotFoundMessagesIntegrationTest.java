package io.floci.aws.services.elbv2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** AWS words ELBv2 not-found errors per operation: single-resource operations quote the ARN, list reads do not. */
@QuarkusTest
class ElbV2NotFoundMessagesIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String BASE = "arn:aws:elasticloadbalancing:us-east-1:" + ACCOUNT + ":";

    @Test
    void notFoundMessagesFollowTheOperationFamily() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String lb = BASE + "loadbalancer/app/ghost-" + suffix + "/0123456789abcdef";
            String tg = BASE + "targetgroup/ghost-" + suffix + "/0123456789abcdef";
            String listener = BASE + "listener/app/ghost-" + suffix + "/0123456789abcdef/0123456789abcdef";
            String rule = BASE + "listener-rule/app/ghost-" + suffix + "/0123456789abcdef/0123456789abcdef/0123456789abcdef";

            // list reads name no value
            expect("LoadBalancerNotFound", "One or more load balancers not found",
                    () -> client.describeLoadBalancers(r -> r.loadBalancerArns(lb)));
            expect("LoadBalancerNotFound", "Load balancers '[ghost-" + suffix + "]' not found",
                    () -> client.describeLoadBalancers(r -> r.names("ghost-" + suffix)));
            expect("TargetGroupNotFound", "One or more target groups not found",
                    () -> client.describeTargetGroups(r -> r.targetGroupArns(tg)));
            expect("ListenerNotFound", "One or more listeners not found",
                    () -> client.describeListeners(r -> r.listenerArns(listener)));
            expect("ListenerNotFound", "One or more listeners not found",
                    () -> client.describeListenerCertificates(r -> r.listenerArn(listener)));
            expect("RuleNotFound", "One or more rules not found",
                    () -> client.describeRules(r -> r.ruleArns(rule)));

            // operations addressing one resource quote its ARN
            expect("LoadBalancerNotFound", "Load balancer '" + lb + "' not found",
                    () -> client.describeLoadBalancerAttributes(r -> r.loadBalancerArn(lb)));
            expect("LoadBalancerNotFound", "Load balancer '" + lb + "' not found",
                    () -> client.setIpAddressType(r -> r.loadBalancerArn(lb).ipAddressType("ipv4")));
            expect("LoadBalancerNotFound", "Load balancer '" + lb + "' not found",
                    () -> client.createListener(r -> r.loadBalancerArn(lb).port(80).protocol("HTTP")
                            .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                                    f.statusCode("200")))));
            expect("TargetGroupNotFound", "Target groups '" + tg + "' not found",
                    () -> client.describeTargetGroupAttributes(r -> r.targetGroupArn(tg)));
            expect("TargetGroupNotFound", "Target groups '" + tg + "' not found",
                    () -> client.describeTargetHealth(r -> r.targetGroupArn(tg)));
            expect("ListenerNotFound", "Listener '" + listener + "' not found",
                    () -> client.modifyListener(r -> r.listenerArn(listener).port(81)));
            expect("RuleNotFound", "Rule '" + rule + "' not found",
                    () -> client.modifyRule(r -> r.ruleArn(rule).conditions(c -> c.field("path-pattern").values("/x"))));
        }
    }

    @Test
    void setRulePrioritiesRefusalsNameNoValue() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            String balancer = client.createLoadBalancer(r -> r
                    .name("sdk-prio-" + UUID.randomUUID().toString().substring(0, 8))
                    .type("application").subnets(List.of("subnet-default-us-east-1-a", "subnet-default-us-east-1-b")))
                    .loadBalancers().get(0).loadBalancerArn();
            try {
                String listener = client.createListener(r -> r.loadBalancerArn(balancer).port(8080).protocol("HTTP")
                        .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                                f.statusCode("200").contentType("text/plain").messageBody("l"))))
                        .listeners().get(0).listenerArn();
                String first = createRule(client, listener, 10);
                String second = createRule(client, listener, 15);
                expect("PriorityInUse", "One or more priorities not found",
                        () -> client.setRulePriorities(r -> r.rulePriorities(p -> p.ruleArn(second).priority(10))));
                expect("RuleNotFound", "One or more priorities not found",
                        () -> client.setRulePriorities(r -> r.rulePriorities(
                                p -> p.ruleArn(first + "-gone").priority(20))));
            } finally {
                client.deleteLoadBalancer(r -> r.loadBalancerArn(balancer));
            }
        }
    }

    private static String createRule(ElasticLoadBalancingV2Client client, String listener, int priority) {
        return client.createRule(r -> r.listenerArn(listener).priority(priority)
                .conditions(c -> c.field("path-pattern").values("/p" + priority))
                .actions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                        f.statusCode("200").contentType("text/plain").messageBody("r"))))
                .rules().get(0).ruleArn();
    }

    private static void expect(String code, String message, Runnable call) {
        AwsServiceException error = assertThrows(AwsServiceException.class, call::run);
        assertEquals(400, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
        assertEquals(message, error.awsErrorDetails().errorMessage());
    }
}
