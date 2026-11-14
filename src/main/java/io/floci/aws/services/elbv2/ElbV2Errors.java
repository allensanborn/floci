package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.AwsException;

import java.util.List;

/**
 * Not-found and priority errors of Elastic Load Balancing v2. AWS words them per operation: an operation that
 * addresses one resource quotes its identifier ("Listener 'arn' not found"), while a read over a list of
 * identifiers names no value ("One or more listeners not found", no trailing period). Messages only: codes and
 * statuses are what callers branch on.
 */
final class ElbV2Errors {
    private ElbV2Errors() {
    }

    static AwsException loadBalancerNotFound(String arn) {
        return notFound("LoadBalancerNotFound", "Load balancer '" + arn + "' not found");
    }

    /** DescribeLoadBalancers selecting by ARN. */
    static AwsException loadBalancerSelectionNotFound() {
        return notFound("LoadBalancerNotFound", "One or more load balancers not found");
    }

    /** DescribeLoadBalancers selecting by name. */
    static AwsException loadBalancersNotFoundByName(List<String> names) {
        return notFound("LoadBalancerNotFound", "Load balancers '[" + String.join(", ", names) + "]' not found");
    }

    static AwsException targetGroupNotFound(String arn) {
        return notFound("TargetGroupNotFound", "Target groups '" + arn + "' not found");
    }

    /** DescribeTargetGroups. */
    static AwsException targetGroupSelectionNotFound() {
        return notFound("TargetGroupNotFound", "One or more target groups not found");
    }

    static AwsException listenerNotFound(String arn) {
        return notFound("ListenerNotFound", "Listener '" + arn + "' not found");
    }

    /** DescribeListeners, DescribeListenerCertificates. */
    static AwsException listenerSelectionNotFound() {
        return notFound("ListenerNotFound", "One or more listeners not found");
    }

    static AwsException ruleNotFound(String arn) {
        return notFound("RuleNotFound", "Rule '" + arn + "' not found");
    }

    /** DescribeRules. */
    static AwsException ruleSelectionNotFound() {
        return notFound("RuleNotFound", "One or more rules not found");
    }

    /** SetRulePriorities: both an unknown rule (RuleNotFound) and a held priority (PriorityInUse). */
    static AwsException rulePrioritiesRefusal(String code) {
        return notFound(code, "One or more priorities not found");
    }

    private static AwsException notFound(String code, String message) {
        return new AwsException(code, message, 400);
    }
}
