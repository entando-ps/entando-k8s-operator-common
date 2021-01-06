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

package org.entando.kubernetes.controller;

import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentSpec;

public final class KubeUtils {

    public static final String UPDATED_ANNOTATION_NAME = "entando.org/updated";//To avoid  http 400s
    public static final String PASSSWORD_KEY = "password";//Funny name because a variable named 'PASSWORD' is considered a vulnerability
    public static final String USERNAME_KEY = "username";
    public static final String DB_JOB_LABEL_NAME = "dbJob";
    public static final String DEFAULT_SERVER_QUALIFIER = "server";
    public static final String URL_KEY = "url";
    public static final String INTERNAL_URL_KEY = "internalUrl";
    public static final String DEFAULT_SERVICE_SUFFIX = "service";
    public static final String DEFAULT_INGRESS_SUFFIX = "ingress";
    public static final String ENTANDO_RESOURCE_ACTION = "entando.resource.action";
    public static final String ENTANDO_RESOURCE_NAMESPACE = "entando.resource.namespace";
    public static final String ENTANDO_RESOURCE_NAME = "entando.resource.name";
    public static final String ENTANDO_APP_ROLE = "entandoApp";
    public static final String ENTANDO_PLUGIN_ROLE = "entandoPlugin";
    public static final String PUBLIC_CLIENT_ID = "entando-web";
    public static final String ENTANDO_DEFAULT_KEYCLOAK_REALM = "entando";
    public static final String ENTANDO_RESOURCE_KIND_LABEL_NAME = "EntandoResourceKind";
    public static final String ENTANDO_RESOURCE_NAMESPACE_LABEL_NAME = "EntandoResourceNamespace";
    public static final String ENTANDO_OPERATOR_DEFAULT_CONFIGMAP_NAME = "entando-operator-default-config-map";
    public static final String DEFAULT_KEYCLOAK_NAME = "default";

    private static final Logger LOGGER = Logger.getLogger(KubeUtils.class.getName());

    private KubeUtils() {
    }

    public static String getKindOf(Class<? extends EntandoBaseCustomResource> c) {
        return c.getSimpleName();
    }

    public static EnvVarSource secretKeyRef(String secretName, String key) {
        return new EnvVarSourceBuilder().withNewSecretKeyRef(key, secretName, Boolean.FALSE).build();
    }

    public static String snakeCaseOf(String in) {
        return in.replace("-", "_").replace(".", "_");
    }

    public static <S extends EntandoDeploymentSpec> Secret generateSecret(EntandoBaseCustomResource<S> resource, String secretName,
            String username) {
        String password = RandomStringUtils.randomAlphanumeric(10);
        return buildSecret(resource, secretName, username, password);
    }

    public static <S extends EntandoDeploymentSpec> Secret buildSecret(EntandoBaseCustomResource<S> resource, String secretName,
            String username,
            String password) {
        return new SecretBuilder()
                .withNewMetadata().withName(secretName)
                .withOwnerReferences(buildOwnerReference(resource))
                .addToLabels(resource.getKind(), resource.getMetadata().getName())
                .endMetadata()
                .addToStringData(USERNAME_KEY, username)
                .addToStringData(PASSSWORD_KEY, password)
                .build();
    }

    public static void ready(String name) {
        try {
            FileUtils.write(new File("/tmp/" + name + ".ready"), "yes", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not create 'ready' file for {0}", name);
        }
    }

    public static <S extends Serializable> OwnerReference buildOwnerReference(EntandoBaseCustomResource<S> entandoCustomResource) {
        return new OwnerReferenceBuilder()
                .withApiVersion(entandoCustomResource.getApiVersion())
                .withBlockOwnerDeletion(true)
                .withController(true)
                .withKind(entandoCustomResource.getKind())
                .withName(entandoCustomResource.getMetadata().getName())
                .withUid(entandoCustomResource.getMetadata().getUid()).build();
    }

    public static String standardIngressName(EntandoCustomResource entandoCustomResource) {
        return entandoCustomResource.getMetadata().getName() + "-" + DEFAULT_INGRESS_SUFFIX;
    }

    public static <S extends Serializable> boolean customResourceOwns(EntandoBaseCustomResource<S> customResource,
            HasMetadata resource) {
        return resource.getMetadata().getOwnerReferences().stream()
                .anyMatch(ownerReference -> customResource.getMetadata().getName().equals(ownerReference.getName())
                        && customResource.getKind().equals(ownerReference.getKind()));
    }

    /**
     * Useful for labelvalues and container names.
     */
    public static String shortenTo64Chars(String s) {
        if (s.length() > 64) {
            s = s.substring(0, 64 - 3) + RandomStringUtils.randomNumeric(3);
        }
        return s;
    }
}
