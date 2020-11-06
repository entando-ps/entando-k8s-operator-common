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

package org.entando.kubernetes.controller.k8sclient;

import static java.lang.String.format;

import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.entando.kubernetes.client.EntandoExecListener;

public interface PodClient extends PodWaitingClient {

    Pod start(Pod pod);

    Pod waitForPod(String namespace, String labelName, String labelValue);

    Pod loadPod(String namespace, String labelName, String labelValue);

    Pod runToCompletion(Pod pod);

    ExecWatch executeOnPod(Pod pod, String containerName, int timeoutSeconds, String... commands);

    @SuppressWarnings({"java:S106"})
    default ExecWatch executeAndWait(PodResource<Pod, DoneablePod> podResource, String containerName, int timeoutSeconds,
            String... script) {
        StringBuilder sb = new StringBuilder();
        for (String s : script) {
            sb.append(s);
            sb.append('\n');
        }
        sb.append("exit 0\n");
        ByteArrayInputStream in = new ByteArrayInputStream(sb.toString().getBytes());
        try {
            Object mutex = new Object();
            synchronized (mutex) {
                EntandoExecListener listener = new EntandoExecListener(mutex, timeoutSeconds);
                getExecListenerHolder().set(listener);
                ExecWatch exec = podResource.inContainer(containerName)
                        .readingInput(in)
                        .writingOutput(System.out)
                        .writingError(System.err)
                        .withTTY()
                        .usingListener(listener)
                        .exec();
                while (listener.shouldStillWait()) {
                    mutex.wait(1000);
                }
                if (listener.hasFailed()) {
                    throw new IllegalStateException(format("Command did not meet the wait condition within 20 seconds: %s", sb.toString()));
                }
                return exec;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * A getter for the an AtomicReference to the most recently constructed ExecListener for testing purposes.
     */
    AtomicReference<EntandoExecListener> getExecListenerHolder();

    void removeAndWait(String namespace, Map<String, String> labels);
}
