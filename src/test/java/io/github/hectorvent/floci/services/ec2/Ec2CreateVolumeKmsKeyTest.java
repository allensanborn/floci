package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CreateVolume dropped KmsKeyId, so Terraform's aws_ebs_volume.kms_key_id never read back and forced a replacement. */
class Ec2CreateVolumeKmsKeyTest {

    private static final String REGION = "us-east-1";
    private static final String KEY = "arn:aws:kms:us-east-1:000000000000:key/0f1e2d3c-4b5a-6978-8091-a2b3c4d5e6f7";

    @Test
    void theKeyIsRecordedReturnedAndRequiresEncryption() {
        Ec2Service service = newService();
        assertEquals(KEY, service.createVolume(REGION, "us-east-1a", "gp3", 20, true, 0, null, null,
                List.of(), KEY).getKmsKeyId());
        assertEquals("InvalidParameterDependency", assertThrows(AwsException.class, () -> service.createVolume(
                REGION, "us-east-1a", "gp3", 20, false, 0, null, null, List.of(), KEY)).getErrorCode());
    }

    @Test
    void createVolumeOnTheWireCarriesTheKeyBack() {
        Ec2QueryHandler handler = new Ec2QueryHandler(newService(), mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
                mock(Ec2SnapshotBlockPublicAccessService.class), mock(Ec2IpamService.class),
                mock(RegionResolver.class));
        MultivaluedMap<String, String> p = new MultivaluedHashMap<>();
        p.putSingle("AvailabilityZone", "us-east-1a");
        p.putSingle("Size", "20");
        p.putSingle("Encrypted", "true");
        p.putSingle("KmsKeyId", KEY);
        Response response = handler.handle("CreateVolume", p, REGION);

        assertEquals(200, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("<kmsKeyId>" + KEY + "</kmsKeyId>"),
                String.valueOf(response.getEntity()));
    }

    private static Ec2Service newService() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory("000000000000"));
    }
}
