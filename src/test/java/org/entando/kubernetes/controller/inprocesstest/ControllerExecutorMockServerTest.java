package org.entando.kubernetes.controller.inprocesstest;

import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.entando.kubernetes.client.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.junit.Rule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tag("in-process")
@EnableRuleMigrationSupport
public class ControllerExecutorMockServerTest extends ControllerExecutorTestBase {

    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);
    private DefaultSimpleK8SClient defaultSimpleK8SClient;

    @Override
    public SimpleK8SClient<?> getClient() {
        if (defaultSimpleK8SClient == null) {
            defaultSimpleK8SClient = new DefaultSimpleK8SClient(server.getClient());
        }
        return defaultSimpleK8SClient;
    }
}