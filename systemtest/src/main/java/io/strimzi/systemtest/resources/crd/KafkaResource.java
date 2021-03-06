/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.resources.crd;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.KafkaExporterResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternal;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalConfiguration;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalIngress;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalLoadBalancer;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalNodePort;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalRoute;
import io.strimzi.api.kafka.model.listener.KafkaListenerTls;
import io.strimzi.api.kafka.model.status.KafkaStatus;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.TestKafkaVersion;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;

public class KafkaResource {
    private static final Logger LOGGER = LogManager.getLogger(KafkaResource.class);

    private static final String PATH_TO_KAFKA_METRICS_CONFIG = "../examples/metrics/kafka-metrics.yaml";
    private static final String PATH_TO_KAFKA_EPHEMERAL_CONFIG = "../examples/kafka/kafka-ephemeral.yaml";
    private static final String PATH_TO_KAFKA_PERSISTENT_CONFIG = "../examples/kafka/kafka-persistent.yaml";

    public static MixedOperation<Kafka, KafkaList, DoneableKafka, Resource<Kafka, DoneableKafka>> kafkaClient() {
        return Crds.kafkaOperation(ResourceManager.kubeClient().getClient());
    }

    public static DoneableKafka kafkaEphemeral(String name, int kafkaReplicas) {
        return kafkaEphemeral(name, kafkaReplicas, 3);
    }

    public static DoneableKafka kafkaEphemeral(String name, int kafkaReplicas, int zookeeperReplicas) {
        Kafka kafka = getKafkaFromYaml(PATH_TO_KAFKA_EPHEMERAL_CONFIG);
        return deployKafka(defaultKafka(kafka, name, kafkaReplicas, zookeeperReplicas).build());
    }

    public static DoneableKafka kafkaPersistent(String name, int kafkaReplicas) {
        return kafkaPersistent(name, kafkaReplicas, 3);
    }

    public static DoneableKafka kafkaPersistent(String name, int kafkaReplicas, int zookeeperReplicas) {
        Kafka kafka = getKafkaFromYaml(PATH_TO_KAFKA_PERSISTENT_CONFIG);
        return deployKafka(defaultKafka(kafka, name, kafkaReplicas, zookeeperReplicas)
            .editSpec()
                .editKafka()
                    .withNewPersistentClaimStorage()
                        .withNewSize("100")
                        .withDeleteClaim(true)
                    .endPersistentClaimStorage()
                .endKafka()
                .editZookeeper()
                    .withNewPersistentClaimStorage()
                        .withNewSize("100")
                        .withDeleteClaim(true)
                    .endPersistentClaimStorage()
                .endZookeeper()
            .endSpec().build());
    }

    public static DoneableKafka kafkaJBOD(String name, int kafkaReplicas, JbodStorage jbodStorage) {
        return kafkaJBOD(name, kafkaReplicas, 3, jbodStorage);
    }

    public static DoneableKafka kafkaJBOD(String name, int kafkaReplicas, int zookeeperReplicas, JbodStorage jbodStorage) {
        Kafka kafka = getKafkaFromYaml(PATH_TO_KAFKA_PERSISTENT_CONFIG);
        return deployKafka(defaultKafka(kafka, name, kafkaReplicas, zookeeperReplicas).
            editSpec()
                .editKafka()
                    .withStorage(jbodStorage)
                .endKafka()
                .editZookeeper().
                    withReplicas(zookeeperReplicas)
                .endZookeeper()
            .endSpec()
            .build());
    }

    public static DoneableKafka kafkaWithMetrics(String name, int kafkaReplicas, int zookeeperReplicas) {
        Kafka kafka = getKafkaFromYaml(PATH_TO_KAFKA_METRICS_CONFIG);
        return deployKafka(defaultKafka(kafka, name, kafkaReplicas, zookeeperReplicas)
            .editSpec()
                .withNewKafkaExporter()
                .endKafkaExporter()
            .endSpec()
            .build());
    }

    public static KafkaBuilder defaultKafka(String name, int kafkaReplicas, int zookeeperReplicas) {
        Kafka kafka = getKafkaFromYaml(PATH_TO_KAFKA_EPHEMERAL_CONFIG);
        return defaultKafka(kafka, name, kafkaReplicas, zookeeperReplicas);
    }

    private static KafkaBuilder defaultKafka(Kafka kafka, String name, int kafkaReplicas, int zookeeperReplicas) {
        String tOImage = StUtils.changeOrgAndTag(ResourceManager.getImageValueFromCO("STRIMZI_DEFAULT_TOPIC_OPERATOR_IMAGE"));
        String uOImage = StUtils.changeOrgAndTag(ResourceManager.getImageValueFromCO("STRIMZI_DEFAULT_USER_OPERATOR_IMAGE"));

        return new KafkaBuilder(kafka)
            .withNewMetadata()
                .withName(name)
                .withNamespace(ResourceManager.kubeClient().getNamespace())
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withVersion(Environment.ST_KAFKA_VERSION)
                    .withReplicas(kafkaReplicas)
                    .addToConfig("log.message.format.version", TestKafkaVersion.getKafkaVersionsInMap().get(Environment.ST_KAFKA_VERSION).protocolVersion())
                    .addToConfig("offsets.topic.replication.factor", Math.min(kafkaReplicas, 3))
                    .addToConfig("transaction.state.log.min.isr", Math.min(kafkaReplicas, 2))
                    .addToConfig("transaction.state.log.replication.factor", Math.min(kafkaReplicas, 3))
                    .withNewListeners()
                        .withNewPlain().endPlain()
                        .withNewTls().endTls()
                    .endListeners()
                    .withNewInlineLogging()
                        .addToLoggers("kafka.root.logger.level", "DEBUG")
                    .endInlineLogging()
                .endKafka()
                .editZookeeper()
                    .withReplicas(zookeeperReplicas)
                    .withNewInlineLogging()
                        .addToLoggers("zookeeper.root.logger", "DEBUG")
                    .endInlineLogging()
                .endZookeeper()
                .editEntityOperator()
                    .editTopicOperator().withImage(tOImage).endTopicOperator()
                    .editUserOperator().withImage(uOImage).endUserOperator()
                    .editUserOperator()
                        .withNewInlineLogging()
                            .addToLoggers("rootLogger.level", "DEBUG")
                        .endInlineLogging()
                    .endUserOperator()
                    .editTopicOperator()
                        .withNewInlineLogging()
                            .addToLoggers("rootLogger.level", "DEBUG")
                        .endInlineLogging()
                    .endTopicOperator()
                .endEntityOperator()
            .endSpec();
    }

    static DoneableKafka deployKafka(Kafka kafka) {
        return new DoneableKafka(kafka, k -> {
            TestUtils.waitFor("Kafka creation", Constants.POLL_INTERVAL_FOR_RESOURCE_CREATION, Constants.TIMEOUT_FOR_CR_CREATION,
                () -> {
                    try {
                        kafkaClient().inNamespace(ResourceManager.kubeClient().getNamespace()).createOrReplace(k);
                        return true;
                    } catch (KubernetesClientException e) {
                        if (e.getMessage().contains("object is being deleted")) {
                            return false;
                        } else {
                            throw e;
                        }
                    }
                });
            return waitFor(deleteLater(k));
        });
    }

    /**
     * This method is used for deploy specific Kafka cluster without wait for all resources.
     * It can be use for example for deploy Kafka cluster with unsupported Kafka version.
     * @param kafka kafka cluster specification
     * @return kafka cluster specification
     */
    public static Kafka kafkaWithoutWait(Kafka kafka) {
        kafkaClient().inNamespace(ResourceManager.kubeClient().getNamespace()).createOrReplace(kafka);
        return kafka;
    }

    /**
     * This method is used for delete specific Kafka cluster without wait for all resources deletion.
     * It can be use for example for delete Kafka cluster CR with unsupported Kafka version.
     * @param kafka kafka cluster specification
     */
    public static void deleteKafkaWithoutWait(Kafka kafka) {
        kafkaClient().inNamespace(ResourceManager.kubeClient().getNamespace()).delete(kafka);
    }

    private static Kafka getKafkaFromYaml(String yamlPath) {
        return TestUtils.configFromYaml(yamlPath, Kafka.class);
    }

    /**
     * Wait until the ZK, Kafka and EO are all ready
     */
    private static Kafka waitFor(Kafka kafka) {
        String kafkaCrName = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();

        LOGGER.info("Waiting for Kafka {} in namespace {}", kafkaCrName, namespace);

        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.zookeeperStatefulSetName(kafkaCrName), kafka.getSpec().getZookeeper().getReplicas());
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(kafkaCrName), kafka.getSpec().getKafka().getReplicas());

        // EO should not be deployed if it does not contain UO and TO
        if (kafka.getSpec().getEntityOperator().getTopicOperator() != null || kafka.getSpec().getEntityOperator().getUserOperator() != null) {
            DeploymentUtils.waitForDeploymentReady(KafkaResources.entityOperatorDeploymentName(kafkaCrName));
        }
        // Kafka Exporter is not setup everytime
        if (kafka.getSpec().getKafkaExporter() != null) {
            DeploymentUtils.waitForDeploymentReady(KafkaExporterResources.deploymentName(kafkaCrName));
        }
        return kafka;
    }

    private static Kafka deleteLater(Kafka kafka) {
        return ResourceManager.deleteLater(kafkaClient(), kafka);
    }

    public static void replaceKafkaResource(String resourceName, Consumer<Kafka> editor) {
        ResourceManager.replaceCrdResource(Kafka.class, KafkaList.class, DoneableKafka.class, resourceName, editor);
    }

    public static String getKafkaTlsListenerCaCertName(String namespace, String clusterName) {
        KafkaListenerTls kafkaListenerTls = kafkaClient().inNamespace(namespace).withName(clusterName).get().getSpec().getKafka().getListeners().getTls();
        return kafkaListenerTls.getConfiguration() == null ?
                KafkaResources.clusterCaCertificateSecretName(clusterName) : kafkaListenerTls.getConfiguration().getBrokerCertChainAndKey().getSecretName();
    }

    public static String getKafkaExternalListenerCaCertName(String namespace, String clusterName) {
        KafkaListenerExternal kafkaListenerExternal = kafkaClient().inNamespace(namespace).withName(clusterName).get().getSpec().getKafka().getListeners().getExternal();

        KafkaListenerExternalConfiguration kafkaListenerExternalConfiguration;

        switch (kafkaListenerExternal.getType()) {
            case KafkaListenerExternalRoute.TYPE_ROUTE:
                kafkaListenerExternalConfiguration = ((KafkaListenerExternalRoute) kafkaListenerExternal).getConfiguration();
                break;
            case KafkaListenerExternalNodePort.TYPE_NODEPORT:
                kafkaListenerExternalConfiguration = ((KafkaListenerExternalNodePort) kafkaListenerExternal).getConfiguration();
                break;
            case KafkaListenerExternalLoadBalancer.TYPE_LOADBALANCER:
                kafkaListenerExternalConfiguration = ((KafkaListenerExternalLoadBalancer) kafkaListenerExternal).getConfiguration();
                break;
            case KafkaListenerExternalIngress.TYPE_INGRESS:
                kafkaListenerExternalConfiguration = ((KafkaListenerExternalIngress) kafkaListenerExternal).getConfiguration();
                break;
            default:
                kafkaListenerExternalConfiguration = null;
                break;
        }

        return kafkaListenerExternalConfiguration == null ?
                KafkaResources.clusterCaCertificateSecretName(clusterName) : kafkaListenerExternalConfiguration.getBrokerCertChainAndKey().getSecretName();
    }

    public static KafkaStatus getKafkaStatus(String clusterName, String namespace) {
        return kafkaClient().inNamespace(namespace).withName(clusterName).get().getStatus();
    }
}
