package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RunInstances dropped EbsOptimized, so an aws_instance with ebs_optimized = true read back false. */
class Ec2RunInstancesEbsOptimizedTest {

    private static final String REGION = "us-east-1";

    @Test
    void ebsOptimizedAtLaunchIsRecordedOnTheInstance() {
        Ec2Service service = mock(Ec2Service.class);
        Instance launched = launch(service, "true");

        verify(service).modifyInstanceAttribute(REGION, "i-0123456789abcdef0", "ebsOptimized", "true");
        assertEquals(true, launched.isEbsOptimized());
    }

    @Test
    void absentLeavesTheInstanceAlone() {
        Ec2Service service = mock(Ec2Service.class);
        launch(service, null);
        verify(service, never()).modifyInstanceAttribute(anyString(), anyString(), eq("ebsOptimized"), anyString());
    }

    private static Instance launch(Ec2Service service, String ebsOptimized) {
        Reservation reservation = new Reservation();
        Instance launched = new Instance();
        launched.setInstanceId("i-0123456789abcdef0");
        reservation.getInstances().add(launched);
        when(service.runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), nullable(String.class)))
                .thenReturn(reservation);
        MultivaluedMap<String, String> p = new MultivaluedHashMap<>();
        p.putSingle("ImageId", "ami-0abcdef1234567890");
        p.putSingle("InstanceType", "t3.micro");
        p.putSingle("MinCount", "1");
        p.putSingle("MaxCount", "1");
        if (ebsOptimized != null) {
            p.putSingle("EbsOptimized", ebsOptimized);
        }
        new Ec2QueryHandler(service, mock(EmulatorConfig.class), mock(FlowLogService.class),
                mock(Ec2EbsEncryptionService.class), mock(Ec2SnapshotBlockPublicAccessService.class),
                mock(Ec2IpamService.class), mock(RegionResolver.class)).handle("RunInstances", p, REGION);
        return launched;
    }
}
