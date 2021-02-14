/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.PodResult.State;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.test.common.EntandoOperatorTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("in-process"), @Tag("pre-deployment"), @Tag("integration")})
@EnableRuleMigrationSupport
class DefaultPodClientTest extends AbstractK8SIntegrationTest {

    private final EntandoApp entandoApp = newTestEntandoApp();

    @BeforeEach
    void cleanup() {
        super.deleteAll(getFabric8Client().apps().deployments());
        super.deleteAll(getFabric8Client().pods());
    }

    @Test
    void shouldWaitForPreviouslyStartedPod() {
        //Given I have started a new Pod
        final Pod startedPod = getSimpleK8SClient().pods().start(new PodBuilder()
                .withNewMetadata()
                .withName("my-pod")
                .withNamespace(entandoApp.getMetadata().getNamespace())
                .addToLabels("pod-label", "123")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withImage("centos/nginx-116-centos7")
                .withName("nginx")
                .withCommand("/usr/libexec/s2i/run")
                .endContainer()
                .endSpec()
                .build());
        //When I wait for the pod
        if (EntandoOperatorTestConfig.emulateKubernetes()) {
            scheduler.schedule(() -> {
                final Pod ready = updatePodWithStatus(startedPod, podWithReadyStatus(startedPod).getStatus());
                takePodWatcherFrom(getSimpleK8SClient().pods()).eventReceived(Action.MODIFIED, ready);
            }, 200, TimeUnit.MILLISECONDS);
        }
        final Pod pod = getSimpleK8SClient().pods().waitForPod(entandoApp.getMetadata().getNamespace(), "pod-label", "123");
        //Then the current thread only proceeds once the pod is ready
        assertThat(PodResult.of(pod).getState(), is(State.READY));
        assertThat(PodResult.of(pod).hasFailed(), is(false));
    }

    @Test
    void shouldRemoveAndWait() {
        //Given I have started a new Pod
        final Pod startedPod = getSimpleK8SClient().pods().start(new PodBuilder()
                .withNewMetadata()
                .withName("my-pod")
                .withNamespace(entandoApp.getMetadata().getNamespace())
                .addToLabels("pod-label", "123")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withImage("centos/nginx-116-centos7")
                .withName("nginx")
                .withCommand("/usr/libexec/s2i/run")
                .endContainer()
                .endSpec()
                .build());
        //When I wait for the pod
        if (EntandoOperatorTestConfig.emulateKubernetes()) {
            scheduler.schedule(() -> {
                takePodWatcherFrom(getSimpleK8SClient().pods()).eventReceived(Action.DELETED, null);
            }, 200, TimeUnit.MILLISECONDS);
        }
        getSimpleK8SClient().pods().removeAndWait(entandoApp.getMetadata().getNamespace(), Collections.singletonMap("pod-label", "123"));
        //Then the current thread only proceeds once the pod is ready
        assertThat(
                getSimpleK8SClient().pods().loadPod(entandoApp.getMetadata().getNamespace(), Collections.singletonMap("pod-label", "123")),
                is(nullValue()));
    }

    @Test
    @Disabled("Currently used for optimization only")
    void testProbes() throws InterruptedException {
        //Given I have started a new Pod
        final Pod startedPod = getSimpleK8SClient().pods().start(new PodBuilder()
                .withNewMetadata()
                .withName("my-pod")
                .withNamespace(entandoApp.getMetadata().getNamespace())
                .addToLabels("pod-label", "123")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withImage("centos/nginx-116-centos7")
                .withName("nginx")
                .withCommand("/usr/libexec/s2i/run")
                //                .withNewStartupProbe()
                //                .withNewExec()
                //                .withCommand("cat", "/tmp/started")
                //                .endExec()
                //                .withPeriodSeconds(5)
                //                .withFailureThreshold(10)
                //                .endStartupProbe()
                .withNewLivenessProbe()
                .withNewExec()
                .withCommand("cat", "/tmp/live")
                .endExec()
                .withInitialDelaySeconds(120)
                .withPeriodSeconds(5)
                .withFailureThreshold(1)
                .withSuccessThreshold(1)
                .endLivenessProbe()
                .withNewReadinessProbe()
                .withNewExec()
                .withCommand("cat", "/tmp/ready")
                .endExec()
                .withPeriodSeconds(5)
                .endReadinessProbe()
                .endContainer()
                .endSpec()
                .build());
        //        getSimpleK8SClient().pods().executeOnPod(startedPod, "nginx", 5, "touch /tmp/started");
        getSimpleK8SClient().pods().executeOnPod(startedPod, "nginx", 5, "touch /tmp/ready");
        getSimpleK8SClient().pods().executeOnPod(startedPod, "nginx", 5, "touch /tmp/live");
        getSimpleK8SClient().pods().executeOnPod(startedPod, "nginx", 5, "rm /tmp/live");
        assertThat(startedPod, is(notNullValue()));
    }

    @Test
    void shouldRunPodToCompletion() {
        //Given I have started a new Pod
        final Pod pod = new PodBuilder()
                .withNewMetadata()
                .withName("my-pod")
                .withNamespace(entandoApp.getMetadata().getNamespace())
                .addToLabels("pod-label", "123")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withImage("busybox")
                .withName("sleep")
                .withCommand("sleep")
                .withArgs("5")
                .endContainer()
                .withRestartPolicy("Never")
                .endSpec()
                .build();
        //When I wait for the pod
        if (EntandoOperatorTestConfig.emulateKubernetes()) {
            scheduler.schedule(() -> {
                final Pod ready = updatePodWithStatus(pod, podWithSucceededStatus(pod).getStatus());
                takePodWatcherFrom(getSimpleK8SClient().pods()).eventReceived(Action.MODIFIED, ready);
            }, 200, TimeUnit.MILLISECONDS);
        }
        final Pod actual = getSimpleK8SClient().pods().runToCompletion(pod);
        //Then the current thread only proceeds once the pod has completed
        assertThat(PodResult.of(actual).getState(), is(State.COMPLETED));
        assertThat(PodResult.of(actual).hasFailed(), is(false));
    }

    private Pod updatePodWithStatus(Pod targetPod, PodStatus status) {
        final PodResource<Pod> resource = getFabric8Client().pods().inNamespace(targetPod.getMetadata().getNamespace())
                .withName(targetPod.getMetadata().getName());
        final Pod reloadedPod = resource.fromServer().get();
        reloadedPod.setStatus(status);
        return resource.updateStatus(reloadedPod);
    }

    @Test
    @DisabledIfSystemProperty(named = EntandoOperatorTestConfig.ENTANDO_TEST_EMULATE_KUBERNETES, matches = "true")
    void shouldExecuteCommandOnPodAndWait() throws IOException {
        //There is no way to emulate this "exec" fails on the Mock server
        //Given I have started a new Pod
        final Pod startedPod = getSimpleK8SClient().pods().start(new PodBuilder()
                .withNewMetadata()
                .withName("my-pod")
                .withNamespace(entandoApp.getMetadata().getNamespace())
                .addToLabels("pod-label", "123")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withImage("centos/nginx-116-centos7")
                .withName("nginx")
                .withCommand("/usr/libexec/s2i/run")
                .endContainer()
                .endSpec()
                .build());
        //When I wait for the pod
        final Pod pod = getSimpleK8SClient().pods().waitForPod(entandoApp.getMetadata().getNamespace(), "pod-label", "123");
        final EntandoExecListener execWatch = getSimpleK8SClient().pods().executeOnPod(pod, "nginx", 10, "echo 'hello world'");
        //Then the current thread only proceeds once the pod is ready
        final List<String> lines = execWatch.getOutput();
        assertThat(lines, hasItem("hello world"));
    }

    @Override
    protected String[] getNamespacesToUse() {
        return new String[]{entandoApp.getMetadata().getNamespace()};
    }
}
