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

package org.entando.kubernetes.controller.common.examples.barebones;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.Arrays;
import java.util.List;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.database.DbmsVendorStrategy;
import org.entando.kubernetes.controller.spi.Deployable;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.Secretive;
import org.entando.kubernetes.model.EntandoCustomResource;

public class BareBonesDeployable implements Deployable<BarebonesDeploymentResult>, Secretive {

    public static final String MY_SERVICE_ACCOUNT = "my-service-account";
    public static final String NAME_QUALIFIER = "db";
    private List<DeployableContainer> containers = Arrays.asList(new BareBonesContainer());
    private EntandoCustomResource customResource;
    private DbmsVendorStrategy dbmsVendor = DbmsVendorStrategy.POSTGRESQL;

    public BareBonesDeployable(EntandoCustomResource customResource) {
        this.customResource = customResource;
    }

    @Override
    public List<Secret> buildSecrets() {
        Secret secret = KubeUtils.generateSecret(customResource, BareBonesContainer.getDatabaseAdminSecretName(),
                dbmsVendor.getDefaultAdminUsername());
        return Arrays.asList(secret);
    }

    /**
     * NB!!! Implementations need to implement this as a non-modifiable list with the exact same instances returned consistently.
     */
    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public String getNameQualifier() {
        return NAME_QUALIFIER;
    }

    @Override
    public EntandoCustomResource getCustomResource() {
        return customResource;
    }

    @Override
    public BarebonesDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new BarebonesDeploymentResult(pod);
    }

    @Override
    public String getServiceAccountName() {
        return MY_SERVICE_ACCOUNT;
    }
}
