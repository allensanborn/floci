package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Which cgroup setup a systemd guest gets, by whether it runs privileged. */
class Ec2SystemdCgroupTest {

    @Test
    void aPrivilegedGuestGetsAPrivateNamespaceAndNoHostTree() {
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        Ec2ContainerManager.applySystemdCgroup(spec, true);
        verify(spec).withCgroupnsMode("private");
        verify(spec, never()).withBind(anyString(), anyString());
    }

    @Test
    void anUnprivilegedGuestUnderSecurityGroupEnforcementKeepsAWritableTree() {
        // Docker mounts an unprivileged container's private cgroupfs read-only, and systemd
        // cannot boot on it.
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        Ec2ContainerManager.applySystemdCgroup(spec, false);
        verify(spec).withCgroupnsMode("host");
        verify(spec).withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
    }
}
