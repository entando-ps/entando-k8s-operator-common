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

package org.entando.kubernetes.controller.creators;

import static org.entando.kubernetes.controller.EntandoOperatorConfigBase.lookupProperty;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.EntandoImageResolver;
import org.entando.kubernetes.controller.database.DatabaseSchemaCreationResult;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.k8sclient.SecretClient;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.spi.DatabasePopulator;
import org.entando.kubernetes.controller.spi.DbAware;
import org.entando.kubernetes.controller.spi.DbAwareDeployable;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentSpec;

public class DatabasePreparationPodCreator<T extends EntandoDeploymentSpec> extends AbstractK8SResourceCreator<T> {

    /**
     * String used to distinguish resources in special cases like name shortening.
     */
    private final String resourceNameDiscriminator;

    public DatabasePreparationPodCreator(EntandoBaseCustomResource<T> entandoCustomResource) {
        super(entandoCustomResource);
        this.resourceNameDiscriminator = RandomStringUtils.randomNumeric(3);    //NOSONAR
    }

    public Pod runToCompletion(SimpleK8SClient<?> client, DbAwareDeployable dbAwareDeployable, EntandoImageResolver entandoImageResolver) {
        client.pods().removeAndWait(
                entandoCustomResource.getMetadata().getNamespace(), buildUniqueLabels(dbAwareDeployable.getNameQualifier()));
        return client.pods().runToCompletion(
                buildJobPod(client.secrets(), entandoImageResolver, dbAwareDeployable, dbAwareDeployable.getNameQualifier()));
    }

    private Pod buildJobPod(SecretClient secretClient, EntandoImageResolver entandoImageResolver, DbAwareDeployable dbAwareDeployable,
            String dbJobName) {
        return new PodBuilder().withNewMetadata()
                .withNamespace(entandoCustomResource.getMetadata().getNamespace())
                .withOwnerReferences(KubeUtils.buildOwnerReference(entandoCustomResource))
                .withLabels(buildUniqueLabels(dbJobName))
                .withName(dbJobName + "-" + UUID.randomUUID().toString().substring(0, 4))
                .endMetadata()
                .withNewSpec()
                .withInitContainers(buildContainers(entandoImageResolver, secretClient, dbAwareDeployable))
                .addNewContainer()
                .withName("dummy")
                .withImage(entandoImageResolver.determineImageUri("entando/busybox"))
                .endContainer()
                .withRestartPolicy("Never")
                .endSpec()
                .build();
    }

    private Map<String, String> buildUniqueLabels(String qualifier) {
        Map<String, String> labelsFromResource = labelsFromResource();
        labelsFromResource.put(KubeUtils.JOB_KIND_LABEL_NAME, KubeUtils.JOB_KIND_DB_PREPARATION);
        labelsFromResource.put(KubeUtils.DEPLOYMENT_QUALIFIER_LABEL_NAME, qualifier);
        return labelsFromResource;
    }

    private List<Container> buildContainers(EntandoImageResolver entandoImageResolver, SecretClient secretClient,
            DbAwareDeployable deployable) {
        List<Container> result = new ArrayList<>();
        for (DbAware dbAware : deployable.getDbAwareContainers()) {
            Optional<DatabasePopulator> databasePopulator = dbAware.useDatabaseSchemas(
                    prepareContainersToCreateSchemas(secretClient, entandoImageResolver, deployable, dbAware, result));
            databasePopulator
                    .ifPresent(dbp -> result.add(prepareContainerToPopulateSchemas(entandoImageResolver, dbp, dbAware.getNameQualifier())));
        }
        return result;
    }

    private Map<String, DatabaseSchemaCreationResult> prepareContainersToCreateSchemas(SecretClient secretClient,
            EntandoImageResolver entandoImageResolver, DbAwareDeployable deployable,
            DbAware dbAware, List<Container> containerList) {
        Map<String, DatabaseSchemaCreationResult> schemaResults = new ConcurrentHashMap<>();
        for (String dbSchemaQualifier : dbAware.getDbSchemaQualifiers()) {
            containerList.add(buildContainerToCreateSchema(entandoImageResolver, deployable.getDatabaseServiceResult(), dbSchemaQualifier));
            schemaResults.put(dbSchemaQualifier, createSchemaResult(deployable.getDatabaseServiceResult(), dbSchemaQualifier));
            createSchemaSecret(secretClient, deployable.getDatabaseServiceResult(), dbSchemaQualifier);
        }
        return schemaResults;
    }

    private Container prepareContainerToPopulateSchemas(EntandoImageResolver entandoImageResolver, DatabasePopulator databasePopulator,
            String nameQualifier) {
        String dbJobName = KubeUtils
                .shortenTo63Chars(entandoCustomResource.getMetadata().getName() + "-" + nameQualifier + "-db-population-job");
        return new ContainerBuilder()
                .withImage(entandoImageResolver.determineImageUri(databasePopulator.getDockerImageInfo()))
                .withImagePullPolicy("Always")
                .withName(dbJobName)
                .withCommand(databasePopulator.getCommand())
                .withEnv(databasePopulator.getEnvironmentVariables()).build();
    }

    private String getSchemaName(DatabaseServiceResult databaseDeployment, String nameQualifier) {
        String schemaName = KubeUtils.snakeCaseOf(entandoCustomResource.getMetadata().getName()) + "_" + nameQualifier;
        if (schemaName.length() > databaseDeployment.getVendor().getVendorConfig().getMaxNameLength()) {
            schemaName = schemaName.substring(0, databaseDeployment.getVendor().getVendorConfig().getMaxNameLength() - 3)
                    + resourceNameDiscriminator;
        }
        return schemaName;
    }

    private String getSchemaSecretName(String nameQualifier) {
        return entandoCustomResource.getMetadata().getName() + "-" + nameQualifier + "-secret";
    }

    private DatabaseSchemaCreationResult createSchemaResult(DatabaseServiceResult databaseDeployment, String nameQualifier) {
        return new DatabaseSchemaCreationResult(databaseDeployment, getSchemaName(databaseDeployment, nameQualifier),
                getSchemaSecretName(nameQualifier));
    }

    private void createSchemaSecret(SecretClient secretClient, DatabaseServiceResult databaseDeployment, String nameQualifier) {
        secretClient.createSecretIfAbsent(entandoCustomResource,
                KubeUtils.generateSecret(entandoCustomResource, getSchemaSecretName(nameQualifier),
                        getSchemaName(databaseDeployment, nameQualifier)));
    }

    private Container buildContainerToCreateSchema(EntandoImageResolver entandoImageResolver,
            DatabaseServiceResult databaseDeployment, String nameQualifier) {
        String dbJobName = KubeUtils
                .shortenTo63Chars(entandoCustomResource.getMetadata().getName() + "-" + nameQualifier + "-schema-creation-job");
        return new ContainerBuilder()
                .withImage(entandoImageResolver
                        .determineImageUri("entando/entando-k8s-dbjob"))
                .withImagePullPolicy("Always")
                .withName(dbJobName)
                .withEnv(buildEnvironment(databaseDeployment, nameQualifier)).build();
    }

    private List<EnvVar> buildEnvironment(DatabaseServiceResult databaseDeployment, String nameQualifier) {
        List<EnvVar> result = new ArrayList<>();
        result.add(new EnvVar("DATABASE_SERVER_HOST", databaseDeployment.getInternalServiceHostname(), null));
        result.add(new EnvVar("DATABASE_SERVER_PORT", databaseDeployment.getPort(), null));
        result.add(new EnvVar("DATABASE_ADMIN_USER", null, buildSecretKeyRef(databaseDeployment, KubeUtils.USERNAME_KEY)));
        result.add(new EnvVar("DATABASE_ADMIN_PASSWORD", null, buildSecretKeyRef(databaseDeployment, KubeUtils.PASSSWORD_KEY)));
        result.add(new EnvVar("DATABASE_NAME", databaseDeployment.getDatabaseName(), null));
        lookupProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_FORCE_DB_PASSWORD_RESET).ifPresent(s ->
                result.add(new EnvVar("FORCE_PASSWORD_RESET", s, null)));
        result.add(new EnvVar("DATABASE_VENDOR", databaseDeployment.getVendor().getVendorConfig().getName(), null));
        result.add(new EnvVar("DATABASE_SCHEMA_COMMAND", "CREATE_SCHEMA", null));
        result.add(new EnvVar("DATABASE_USER", null,
                KubeUtils.secretKeyRef(getSchemaSecretName(nameQualifier), KubeUtils.USERNAME_KEY)));
        result.add(new EnvVar("DATABASE_PASSWORD", null,
                KubeUtils.secretKeyRef(getSchemaSecretName(nameQualifier), KubeUtils.PASSSWORD_KEY)));
        databaseDeployment.getTablespace().ifPresent(s -> result.add(new EnvVar("TABLESPACE", s, null)));
        if (!databaseDeployment.getJdbcParameters().isEmpty()) {
            result.add(new EnvVar("JDBC_PARAMETERS", databaseDeployment.getJdbcParameters().entrySet()
                    .stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(",")), null));
        }
        return result;
    }

    private EnvVarSource buildSecretKeyRef(DatabaseServiceResult databaseDeployment, String configKey) {
        return KubeUtils.secretKeyRef(databaseDeployment.getDatabaseSecretName(), configKey);
    }

}
