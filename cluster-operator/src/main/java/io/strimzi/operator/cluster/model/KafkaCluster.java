/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.strimzi.certs.Cert;
import io.strimzi.certs.CertManager;
import io.strimzi.certs.OpenSslCertManager;
import io.strimzi.certs.Subject;
import io.strimzi.operator.cluster.ClusterOperator;
import io.strimzi.operator.cluster.operator.assembly.AbstractAssemblyOperator;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class KafkaCluster extends AbstractModel {

    public static final String KAFKA_SERVICE_ACCOUNT = "strimzi-kafka";

    protected static final String INIT_NAME = "init-kafka";
    protected static final String RACK_VOLUME_NAME = "rack-volume";
    protected static final String RACK_VOLUME_MOUNT = "/opt/kafka/rack";
    private static final String ENV_VAR_INIT_KAFKA_RACK_TOPOLOGY_KEY = "RACK_TOPOLOGY_KEY";
    private static final String ENV_VAR_INIT_KAFKA_NODE_NAME = "NODE_NAME";

    protected static final int CLIENT_PORT = 9092;
    protected static final String CLIENT_PORT_NAME = "clients";

    protected static final int REPLICATION_PORT = 9091;
    protected static final String REPLICATION_PORT_NAME = "replication";

    private static final String NAME_SUFFIX = "-kafka";
    private static final String HEADLESS_NAME_SUFFIX = NAME_SUFFIX + "-headless";
    private static final String METRICS_CONFIG_SUFFIX = NAME_SUFFIX + "-metrics-config";
    private static final String CLIENTS_CA_SUFFIX = NAME_SUFFIX + "-clients-ca";
    private static final String BROKERS_INTERNAL_SUFFIX = NAME_SUFFIX + "-brokers-internal";
    private static final String BROKERS_CLIENTS_SUFFIX = NAME_SUFFIX + "-brokers-clients";
    private static final String CLIENTS_PUBLIC_KEY_SUFFIX = NAME_SUFFIX + "-cert";

    // Kafka configuration
    private String zookeeperConnect = DEFAULT_KAFKA_ZOOKEEPER_CONNECT;
    private RackConfig rackConfig;
    private String initImage;

    // Configuration defaults
    private static final String DEFAULT_IMAGE =
            System.getenv().getOrDefault("STRIMZI_DEFAULT_KAFKA_IMAGE", "strimzi/kafka:latest");
    private static final String DEFAULT_INIT_IMAGE =
            System.getenv().getOrDefault("STRIMZI_DEFAULT_INIT_KAFKA_IMAGE", "strimzi/init-kafka:latest");


    private static final int DEFAULT_REPLICAS = 3;
    private static final int DEFAULT_HEALTHCHECK_DELAY = 15;
    private static final int DEFAULT_HEALTHCHECK_TIMEOUT = 5;
    private static final boolean DEFAULT_KAFKA_METRICS_ENABLED = false;

    // Kafka configuration defaults
    private static final String DEFAULT_KAFKA_ZOOKEEPER_CONNECT = "zookeeper:2181";

    // Configuration keys (in ConfigMap)
    public static final String KEY_IMAGE = "kafka-image";
    public static final String KEY_REPLICAS = "kafka-nodes";
    public static final String KEY_HEALTHCHECK_DELAY = "kafka-healthcheck-delay";
    public static final String KEY_HEALTHCHECK_TIMEOUT = "kafka-healthcheck-timeout";
    public static final String KEY_METRICS_CONFIG = "kafka-metrics-config";
    public static final String KEY_STORAGE = "kafka-storage";
    public static final String KEY_KAFKA_CONFIG = "kafka-config";
    public static final String KEY_JVM_OPTIONS = "kafka-jvmOptions";
    public static final String KEY_RESOURCES = "kafka-resources";
    public static final String KEY_RACK = "kafka-rack";
    public static final String KEY_INIT_IMAGE = "init-kafka-image";
    public static final String KEY_AFFINITY = "kafka-affinity";

    // Kafka configuration keys (EnvVariables)
    public static final String ENV_VAR_KAFKA_ZOOKEEPER_CONNECT = "KAFKA_ZOOKEEPER_CONNECT";
    private static final String ENV_VAR_KAFKA_METRICS_ENABLED = "KAFKA_METRICS_ENABLED";
    protected static final String ENV_VAR_KAFKA_CONFIGURATION = "KAFKA_CONFIGURATION";
    private static final String ENV_VAR_KAFKA_ENCRYPTION_ENABLED = "KAFKA_ENCRYPTION_ENABLED";

    private Cert internalCA;
    private Cert clientsCA;
    private Map<String, Cert> internalCerts;
    private Map<String, Cert> clientsCerts;

    /**
     * Constructor
     *
     * @param namespace Kubernetes/OpenShift namespace where Kafka cluster resources are going to be created
     * @param cluster  overall cluster name
     */
    private KafkaCluster(String namespace, String cluster, Labels labels) {

        super(namespace, cluster, labels);
        this.name = kafkaClusterName(cluster);
        this.headlessName = headlessName(cluster);
        this.metricsConfigName = metricConfigsName(cluster);
        this.image = DEFAULT_IMAGE;
        this.replicas = DEFAULT_REPLICAS;
        this.healthCheckPath = "/opt/kafka/kafka_healthcheck.sh";
        this.healthCheckTimeout = DEFAULT_HEALTHCHECK_TIMEOUT;
        this.healthCheckInitialDelay = DEFAULT_HEALTHCHECK_DELAY;
        this.isMetricsEnabled = DEFAULT_KAFKA_METRICS_ENABLED;

        this.mountPath = "/var/lib/kafka";
        this.metricsConfigVolumeName = "kafka-metrics-config";
        this.metricsConfigMountPath = "/opt/prometheus/config/";

        this.initImage = DEFAULT_INIT_IMAGE;
    }

    public static String kafkaClusterName(String cluster) {
        return cluster + KafkaCluster.NAME_SUFFIX;
    }

    public static String metricConfigsName(String cluster) {
        return cluster + KafkaCluster.METRICS_CONFIG_SUFFIX;
    }

    public static String headlessName(String cluster) {
        return cluster + KafkaCluster.HEADLESS_NAME_SUFFIX;
    }

    public static String kafkaPodName(String cluster, int pod) {
        return kafkaClusterName(cluster) + "-" + pod;
    }

    public static String clientsCASecretName(String cluster) {
        return cluster + KafkaCluster.CLIENTS_CA_SUFFIX;
    }

    public static String brokersInternalSecretName(String cluster) {
        return cluster + KafkaCluster.BROKERS_INTERNAL_SUFFIX;
    }

    public static String brokersClientsSecret(String cluster) {
        return cluster + KafkaCluster.BROKERS_CLIENTS_SUFFIX;
    }

    public static String clientsPublicKeyName(String cluster) {
        return cluster + KafkaCluster.CLIENTS_PUBLIC_KEY_SUFFIX;
    }

    /**
     * Create a Kafka cluster from the related ConfigMap resource
     *
     * @param kafkaClusterCm ConfigMap with cluster configuration
     * @param secrets Secrets related to the cluster
     * @return Kafka cluster instance
     */
    public static KafkaCluster fromConfigMap(ConfigMap kafkaClusterCm, List<Secret> secrets) {
        KafkaCluster kafka = new KafkaCluster(kafkaClusterCm.getMetadata().getNamespace(),
                kafkaClusterCm.getMetadata().getName(),
                Labels.fromResource(kafkaClusterCm));

        Map<String, String> data = kafkaClusterCm.getData();
        kafka.setReplicas(Utils.getInteger(data, KEY_REPLICAS, DEFAULT_REPLICAS));
        kafka.setImage(Utils.getNonEmptyString(data, KEY_IMAGE, DEFAULT_IMAGE));
        kafka.setHealthCheckInitialDelay(Utils.getInteger(data, KEY_HEALTHCHECK_DELAY, DEFAULT_HEALTHCHECK_DELAY));
        kafka.setHealthCheckTimeout(Utils.getInteger(data, KEY_HEALTHCHECK_TIMEOUT, DEFAULT_HEALTHCHECK_TIMEOUT));

        kafka.setZookeeperConnect(kafkaClusterCm.getMetadata().getName() + "-zookeeper:2181");

        JsonObject metricsConfig = Utils.getJson(data, KEY_METRICS_CONFIG);
        kafka.setMetricsEnabled(metricsConfig != null);
        if (kafka.isMetricsEnabled()) {
            kafka.setMetricsConfig(metricsConfig);
        }

        kafka.setStorage(Utils.getStorage(data, KEY_STORAGE));

        kafka.setConfiguration(Utils.getKafkaConfiguration(data, KEY_KAFKA_CONFIG));

        kafka.setResources(Resources.fromJson(data.get(KEY_RESOURCES)));
        kafka.setJvmOptions(JvmOptions.fromJson(data.get(KEY_JVM_OPTIONS)));

        RackConfig rackConfig = RackConfig.fromJson(data.get(KEY_RACK));
        if (rackConfig != null) {
            kafka.setRackConfig(rackConfig);
        }
        kafka.setInitImage(Utils.getNonEmptyString(data, KEY_INIT_IMAGE, DEFAULT_INIT_IMAGE));
        kafka.setUserAffinity(Utils.getAffinity(data.get(KEY_AFFINITY)));

        // TODO: checking configuration for enabling TLS
        kafka.setEncryptionEnabled(true);
        if (kafka.isEncryptionEnabled()) {
            kafka.generateCertificates(secrets);
        }

        return kafka;
    }

    /**
     * Create a Kafka cluster from the deployed StatefulSet resource
     *
     * @param ss The StatefulSet from which the cluster state should be recovered.
     * @param namespace Kubernetes/OpenShift namespace where cluster resources belong to
     * @param cluster   overall cluster name
     * @return  Kafka cluster instance
     */
    public static KafkaCluster fromAssembly(StatefulSet ss, String namespace, String cluster) {

        KafkaCluster kafka = new KafkaCluster(namespace, cluster, Labels.fromResource(ss));

        kafka.setReplicas(ss.getSpec().getReplicas());
        Container container = ss.getSpec().getTemplate().getSpec().getContainers().get(0);
        kafka.setImage(container.getImage());
        kafka.setHealthCheckInitialDelay(container.getReadinessProbe().getInitialDelaySeconds());
        kafka.setHealthCheckTimeout(container.getReadinessProbe().getTimeoutSeconds());

        Map<String, String> vars = containerEnvVars(container);

        kafka.setZookeeperConnect(vars.getOrDefault(ENV_VAR_KAFKA_ZOOKEEPER_CONNECT, ss.getMetadata().getName() + "-zookeeper:2181"));

        kafka.setMetricsEnabled(Utils.getBoolean(vars, ENV_VAR_KAFKA_METRICS_ENABLED, DEFAULT_KAFKA_METRICS_ENABLED));
        if (kafka.isMetricsEnabled()) {
            kafka.setMetricsConfigName(metricConfigsName(cluster));
        }

        if (!ss.getSpec().getVolumeClaimTemplates().isEmpty()) {

            Storage storage = Storage.fromPersistentVolumeClaim(ss.getSpec().getVolumeClaimTemplates().get(0));
            if (ss.getMetadata().getAnnotations() != null) {
                String deleteClaimAnnotation = String.format("%s/%s", ClusterOperator.STRIMZI_CLUSTER_OPERATOR_DOMAIN, Storage.DELETE_CLAIM_FIELD);
                storage.withDeleteClaim(Boolean.valueOf(ss.getMetadata().getAnnotations().computeIfAbsent(deleteClaimAnnotation, s -> "false")));
            }
            kafka.setStorage(storage);
        } else {
            Storage storage = new Storage(Storage.StorageType.EPHEMERAL);
            kafka.setStorage(storage);
        }

        String kafkaConfiguration = containerEnvVars(container).get(ENV_VAR_KAFKA_CONFIGURATION);
        if (kafkaConfiguration != null) {
            kafka.setConfiguration(new KafkaConfiguration(kafkaConfiguration));
        }

        Affinity affinity = ss.getSpec().getTemplate().getSpec().getAffinity();
        if (affinity != null
                && affinity.getPodAntiAffinity() != null
                && affinity.getPodAntiAffinity().getPreferredDuringSchedulingIgnoredDuringExecution() != null) {
            String rackTopologyKey = affinity.getPodAntiAffinity().getPreferredDuringSchedulingIgnoredDuringExecution().get(0).getPodAffinityTerm().getTopologyKey();
            kafka.setRackConfig(new RackConfig(rackTopologyKey));
        }

        List<Container> initContainers = ss.getSpec().getTemplate().getSpec().getInitContainers();
        if (initContainers != null && !initContainers.isEmpty()) {

            initContainers.stream()
                    .filter(ic -> ic.getName().equals(INIT_NAME))
                    .forEach(ic -> kafka.setInitImage(ic.getImage()));
        }

        return kafka;
    }

    /**
     * Decode from Base64 a keyed value from a Secret
     *
     * @param secret Secret from which decoding the value
     * @param key Key of the value to decode
     * @return decoded value
     */
    private byte[] decodeFromSecret(Secret secret, String key) {
        return Base64.getDecoder().decode(secret.getData().get(key));
    }

    public void generateCertificates(List<Secret> secrets) {
        log.info("Generating certificates");

        try {

            Optional<Secret> internalCAsecret = secrets.stream().filter(s -> s.getMetadata().getName().equals(AbstractAssemblyOperator.INTERNAL_CA_NAME))
                    .findFirst();
            if (internalCAsecret.isPresent()) {

                CertManager certManager = new OpenSslCertManager();

                // get the generated CA private + certificate for internal communications
                internalCA = new Cert(
                        decodeFromSecret(internalCAsecret.get(), "internal-ca.key"),
                        decodeFromSecret(internalCAsecret.get(), "internal-ca.crt"));

                // CA private key + certificate for clients communications
                Optional<Secret> clientsCAsecret = secrets.stream().filter(s -> s.getMetadata().getName().equals(KafkaCluster.clientsCASecretName(cluster))).findFirst();
                if (!clientsCAsecret.isPresent()) {
                    log.info("Clients CA to generate");
                    File clientsCAkeyFile = File.createTempFile("tls", "clients-ca-key");
                    File clientsCAcertFile = File.createTempFile("tls", "clients-ca-cert");
                    certManager.generateSelfSignedCert(clientsCAkeyFile, clientsCAcertFile, DEFAULT_CERTS_EXPIRATION_DAYS);
                    clientsCA =
                            new Cert(Files.readAllBytes(clientsCAkeyFile.toPath()), Files.readAllBytes(clientsCAcertFile.toPath()));
                    if (!clientsCAkeyFile.delete()) {
                        log.warn("{} cannot be deleted", clientsCAkeyFile.getName());
                    }
                    if (!clientsCAcertFile.delete()) {
                        log.warn("{} cannot be deleted", clientsCAcertFile.getName());
                    }
                } else {
                    log.info("Clients CA already exists");
                    clientsCA = new Cert(
                            decodeFromSecret(clientsCAsecret.get(), "clients-ca.key"),
                            decodeFromSecret(clientsCAsecret.get(), "clients-ca.crt"));
                }

                internalCerts = new HashMap<>();
                clientsCerts = new HashMap<>();

                File brokerCsrFile = File.createTempFile("tls", "broker-csr");
                File brokerKeyFile = File.createTempFile("tls", "broker-key");
                File brokerCertFile = File.createTempFile("tls", "broker-cert");

                int replicasInSecret = 0;
                Optional<Secret> internalSecret = secrets.stream().filter(s -> s.getMetadata().getName().equals(KafkaCluster.brokersInternalSecretName(cluster)))
                        .findFirst();
                Optional<Secret> clientsSecret = secrets.stream().filter(s -> s.getMetadata().getName().equals(KafkaCluster.brokersClientsSecret(cluster)))
                        .findFirst();

                if (internalSecret.isPresent() ^ clientsSecret.isPresent()) {
                    log.error("Inconsistency!!!");
                    // TODO: what to do ? raising exception ?
                } else if (internalSecret.isPresent() && clientsSecret.isPresent()) {

                    // internalSecret contains private and public keys for each broker + CA cert for internal communication
                    // clientsSecret contains private and public keys for each broker + CA cert for internal communication + CA cert for clients
                    // comparing the sizes not considering CA certs
                    if ((internalSecret.get().getData().size() - 1) != (clientsSecret.get().getData().size() - 2)) {
                        log.error("Inconsistency!!!");
                        // TODO: what to do ? raising exception ?
                    } else {
                        replicasInSecret = (internalSecret.get().getData().size() - 1) / 2;
                    }
                }

                // copying the minimum number of certificates already existing in the secret
                // scale up -> it will copy all certificates
                // scale down -> it will copy just the requested number of replicas
                for (int i = 0; i < Math.min(replicasInSecret, replicas); i++) {
                    log.info("{} already exists", KafkaCluster.kafkaPodName(cluster, i));
                    internalCerts.put(
                            KafkaCluster.kafkaPodName(cluster, i),
                            new Cert(
                                    decodeFromSecret(internalSecret.get(), KafkaCluster.kafkaPodName(cluster, i) + ".key"),
                                    decodeFromSecret(internalSecret.get(), KafkaCluster.kafkaPodName(cluster, i) + ".crt")));

                    clientsCerts.put(
                            KafkaCluster.kafkaPodName(cluster, i),
                            new Cert(
                                    decodeFromSecret(clientsSecret.get(), KafkaCluster.kafkaPodName(cluster, i) + ".key"),
                                    decodeFromSecret(clientsSecret.get(), KafkaCluster.kafkaPodName(cluster, i) + ".crt")));
                }

                // generate the missing number of certificates
                // scale up -> generate new certificates for added replicas
                // scale down -> does nothing
                for (int i = replicasInSecret; i < replicas; i++) {
                    log.info("{} to generate", KafkaCluster.kafkaPodName(cluster, i));

                    Subject sbj = new Subject();
                    sbj.setOrganizationName("io.strimzi");
                    sbj.setCommonName(KafkaCluster.kafkaPodName(cluster, i));

                    certManager.generateCsr(brokerKeyFile, brokerCsrFile, sbj);
                    certManager.generateCert(brokerCsrFile, internalCA.key(), internalCA.cert(), brokerCertFile, DEFAULT_CERTS_EXPIRATION_DAYS);

                    internalCerts.put(KafkaCluster.kafkaPodName(cluster, i),
                            new Cert(Files.readAllBytes(brokerKeyFile.toPath()), Files.readAllBytes(brokerCertFile.toPath())));

                    certManager.generateCsr(brokerKeyFile, brokerCsrFile, sbj);
                    certManager.generateCert(brokerCsrFile, clientsCA.key(), clientsCA.cert(), brokerCertFile, DEFAULT_CERTS_EXPIRATION_DAYS);

                    clientsCerts.put(KafkaCluster.kafkaPodName(cluster, i),
                            new Cert(Files.readAllBytes(brokerKeyFile.toPath()), Files.readAllBytes(brokerCertFile.toPath())));
                }

                if (!brokerCsrFile.delete()) {
                    log.warn("{} cannot be deleted", brokerCsrFile.getName());
                }
                if (!brokerKeyFile.delete()) {
                    log.warn("{} cannot be deleted", brokerKeyFile.getName());
                }
                if (!brokerCertFile.delete()) {
                    log.warn("{} cannot be deleted", brokerCertFile.getName());
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        log.info("End generating certificates");
    }

    /**
     * Generates ports for bootstrap service.
     * The bootstrap service contains only the client interfaces.
     * Not the replication interface which doesn't need bootstrap service.
     *
     * @return List with generated ports
     */
    private List<ServicePort> getServicePorts() {
        List<ServicePort> ports = new ArrayList<>(2);
        ports.add(createServicePort(CLIENT_PORT_NAME, CLIENT_PORT, CLIENT_PORT, "TCP"));
        if (isMetricsEnabled()) {
            ports.add(createServicePort(metricsPortName, metricsPort, metricsPort, "TCP"));
        }
        return ports;
    }

    /**
     * Generates ports for headless service.
     * The headless service contains both the client interfaces as well as replication interface.
     *
     * @return List with generated ports
     */
    private List<ServicePort> getHeadlessServicePorts() {
        List<ServicePort> ports = new ArrayList<>(2);
        ports.add(createServicePort(CLIENT_PORT_NAME, CLIENT_PORT, CLIENT_PORT, "TCP"));
        ports.add(createServicePort(REPLICATION_PORT_NAME, REPLICATION_PORT, REPLICATION_PORT, "TCP"));
        return ports;
    }

    /**
     * Generates a Service according to configured defaults
     * @return The generated Service
     */
    public Service generateService() {

        return createService("ClusterIP", getServicePorts());
    }

    /**
     * Generates a headless Service according to configured defaults
     * @return The generated Service
     */
    public Service generateHeadlessService() {
        Map<String, String> annotations = Collections.singletonMap("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");
        return createHeadlessService(headlessName, getHeadlessServicePorts(), annotations);
    }

    /**
     * Generates a StatefulSet according to configured defaults
     * @param isOpenShift True iff this operator is operating within OpenShift.
     * @return The generate StatefulSet
     */
    public StatefulSet generateStatefulSet(boolean isOpenShift) {

        return createStatefulSet(
                getContainerPortList(),
                getVolumes(),
                getVolumeClaims(),
                getVolumeMounts(),
                createExecProbe(healthCheckPath, healthCheckInitialDelay, healthCheckTimeout),
                createExecProbe(healthCheckPath, healthCheckInitialDelay, healthCheckTimeout),
                resources(),
                getMergedAffinity(),
                getInitContainers(),
                isOpenShift);
    }


    /**
     * Generates a metrics ConfigMap according to configured defaults
     * @return The generated ConfigMap
     */
    public ConfigMap generateMetricsConfigMap() {
        if (isMetricsEnabled()) {
            Map<String, String> data = new HashMap<>();
            data.put(METRICS_CONFIG_FILE, getMetricsConfig().toString());
            return createConfigMap(getMetricsConfigName(), data);
        } else {
            return null;
        }
    }

    /**
     * Generate the Secret containing CA private key and self-signed certificate used
     * for signing brokers certificates used for communication with clients
     * @return The generated Secret
     */
    public Secret generateClientsCASecret() {
        if (isEncryptionEnabled()) {
            Map<String, String> data = new HashMap<>();
            data.put("clients-ca.key", Base64.getEncoder().encodeToString(clientsCA.key()));
            data.put("clients-ca.crt", Base64.getEncoder().encodeToString(clientsCA.cert()));
            return createSecret(KafkaCluster.clientsCASecretName(cluster), data);
        } else {
            return null;
        }
    }

    /**
     * Generate the Secret containing just the self-signed CA certificate used
     * for signing brokers certificates used for communication with clients
     * It's useful for users to extract the certificate itself to put as trusted on the clients
     * @return The generated Secret
     */
    public Secret generateClientsPublicKeySecret() {
        if (isEncryptionEnabled()) {
            Map<String, String> data = new HashMap<>();
            data.put("clients-ca.crt", Base64.getEncoder().encodeToString(clientsCA.cert()));
            return createSecret(KafkaCluster.clientsPublicKeyName(cluster), data);
        } else {
            return null;
        }
    }

    /**
     * Generate the Secret containing CA self-signed certificate for internal communication.
     * It also contains the private key-certificate (signed by internal CA) for each brokers for communicating
     * internally with Zookeeper as well
     * @return The generated Secret
     */
    public Secret generateBrokersInternalSecret() {
        if (isEncryptionEnabled()) {
            Base64.Encoder encoder = Base64.getEncoder();

            Map<String, String> data = new HashMap<>();
            data.put("internal-ca.crt", encoder.encodeToString(internalCA.cert()));

            for (int i = 0; i < replicas; i++) {
                Cert cert = internalCerts.get(KafkaCluster.kafkaPodName(cluster, i));
                data.put(KafkaCluster.kafkaPodName(cluster, i) + ".key", encoder.encodeToString(cert.key()));
                data.put(KafkaCluster.kafkaPodName(cluster, i) + ".crt", encoder.encodeToString(cert.cert()));
            }
            return createSecret(KafkaCluster.brokersInternalSecretName(cluster), data);
        } else {
            return null;
        }
    }

    /**
     * Generate the Secret containing CA self-signed certificates for internal and clients communication.
     * It also contains the private key-certificate (signed by clients CA) for each brokers for communicating
     * with clients
     * @return The generated Secret
     */
    public Secret generateBrokersClientsSecret() {
        if (isEncryptionEnabled()) {
            Base64.Encoder encoder = Base64.getEncoder();

            Map<String, String> data = new HashMap<>();
            data.put("internal-ca.crt", encoder.encodeToString(internalCA.cert()));
            data.put("clients-ca.crt", encoder.encodeToString(clientsCA.cert()));

            for (int i = 0; i < replicas; i++) {
                Cert cert = clientsCerts.get(KafkaCluster.kafkaPodName(cluster, i));
                data.put(KafkaCluster.kafkaPodName(cluster, i) + ".key", encoder.encodeToString(cert.key()));
                data.put(KafkaCluster.kafkaPodName(cluster, i) + ".crt", encoder.encodeToString(cert.cert()));
            }
            return createSecret(KafkaCluster.brokersClientsSecret(cluster), data);
        } else {
            return null;
        }
    }

    private List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(3);
        portList.add(createContainerPort(CLIENT_PORT_NAME, CLIENT_PORT, "TCP"));
        portList.add(createContainerPort(REPLICATION_PORT_NAME, REPLICATION_PORT, "TCP"));
        if (isMetricsEnabled) {
            portList.add(createContainerPort(metricsPortName, metricsPort, "TCP"));
        }

        return portList;
    }

    private List<Volume> getVolumes() {
        List<Volume> volumeList = new ArrayList<>();
        if (storage.type() == Storage.StorageType.EPHEMERAL) {
            volumeList.add(createEmptyDirVolume(VOLUME_NAME));
        }
        if (isMetricsEnabled) {
            volumeList.add(createConfigMapVolume(metricsConfigVolumeName, metricsConfigName));
        }
        if (rackConfig != null) {
            volumeList.add(createEmptyDirVolume(RACK_VOLUME_NAME));
        }
        if (isEncryptionEnabled) {
            volumeList.add(createSecretVolume("internal-certs", KafkaCluster.brokersInternalSecretName(cluster)));
            volumeList.add(createSecretVolume("clients-certs", KafkaCluster.brokersClientsSecret(cluster)));
        }

        return volumeList;
    }

    private List<PersistentVolumeClaim> getVolumeClaims() {
        List<PersistentVolumeClaim> pvcList = new ArrayList<>();
        if (storage.type() == Storage.StorageType.PERSISTENT_CLAIM) {
            pvcList.add(createPersistentVolumeClaim(VOLUME_NAME));
        }
        return pvcList;
    }

    private List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>();
        volumeMountList.add(createVolumeMount(VOLUME_NAME, mountPath));
        if (isMetricsEnabled) {
            volumeMountList.add(createVolumeMount(metricsConfigVolumeName, metricsConfigMountPath));
        }
        if (rackConfig != null) {
            volumeMountList.add(createVolumeMount(RACK_VOLUME_NAME, RACK_VOLUME_MOUNT));
        }
        if (isEncryptionEnabled) {
            volumeMountList.add(createVolumeMount("internal-certs", "/opt/kafka/internal-certs"));
            volumeMountList.add(createVolumeMount("clients-certs", "/opt/kafka/clients-certs"));
        }

        return volumeMountList;
    }

    /**
     * Returns a combined affinity: Adding the affinity needed for the "kafka-rack" to the {@link #getUserAffinity()}.
     */
    @Override
    protected Affinity getMergedAffinity() {
        Affinity userAffinity = getUserAffinity();
        AffinityBuilder builder = new AffinityBuilder(userAffinity == null ? new Affinity() : userAffinity);
        if (rackConfig != null) {
            // If there's a rack config, we need to add a podAntiAffinity to spread the brokers among the racks
            builder = builder
                    .editOrNewPodAntiAffinity()
                        .addNewPreferredDuringSchedulingIgnoredDuringExecution()
                            .withWeight(100)
                            .withNewPodAffinityTerm()
                                .withTopologyKey(rackConfig.getTopologyKey())
                                .withNewLabelSelector()
                                    .addToMatchLabels(Labels.STRIMZI_CLUSTER_LABEL, cluster)
                                    .addToMatchLabels(Labels.STRIMZI_TYPE_LABEL, AssemblyType.KAFKA.toString())
                                    .addToMatchLabels(Labels.STRIMZI_NAME_LABEL, name)
                                .endLabelSelector()
                            .endPodAffinityTerm()
                        .endPreferredDuringSchedulingIgnoredDuringExecution()
                    .endPodAntiAffinity();
        }
        return builder.build();
    }

    @Override
    protected List<Container> getInitContainers() {

        List<Container> initContainers = new ArrayList<>();

        if (rackConfig != null) {

            ResourceRequirements resources = new ResourceRequirementsBuilder()
                    .addToRequests("cpu", new Quantity("100m"))
                    .addToRequests("memory", new Quantity("128Mi"))
                    .addToLimits("cpu", new Quantity("1"))
                    .addToLimits("memory", new Quantity("256Mi"))
                    .build();

            List<EnvVar> varList =
                    Arrays.asList(buildEnvVarFromFieldRef(ENV_VAR_INIT_KAFKA_NODE_NAME, "spec.nodeName"),
                            buildEnvVar(ENV_VAR_INIT_KAFKA_RACK_TOPOLOGY_KEY, rackConfig.getTopologyKey()));

            Container initContainer = new ContainerBuilder()
                    .withName(INIT_NAME)
                    .withImage(initImage)
                    .withResources(resources)
                    .withEnv(varList)
                    .withVolumeMounts(createVolumeMount(RACK_VOLUME_NAME, RACK_VOLUME_MOUNT))
                    .build();

            initContainers.add(initContainer);
        }

        return initContainers;
    }

    @Override
    protected String getServiceAccountName() {
        return KAFKA_SERVICE_ACCOUNT;
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVar(ENV_VAR_KAFKA_ZOOKEEPER_CONNECT, zookeeperConnect));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_METRICS_ENABLED, String.valueOf(isMetricsEnabled)));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_ENCRYPTION_ENABLED, String.valueOf(isEncryptionEnabled)));
        heapOptions(varList, 0.5, 5L * 1024L * 1024L * 1024L);
        jvmPerformanceOptions(varList);

        if (configuration != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_CONFIGURATION, configuration.getConfiguration()));
        }

        return varList;
    }

    protected void setZookeeperConnect(String zookeeperConnect) {
        this.zookeeperConnect = zookeeperConnect;
    }

    protected void setRackConfig(RackConfig rackConfig) {
        this.rackConfig = rackConfig;
    }

    protected void setInitImage(String initImage) {
        this.initImage = initImage;
    }
}