package dev.themajorones.atw.service.handler;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.themajorones.atw.repository.AndroidRepository;
import dev.themajorones.atw.repository.DockerRepository;
import dev.themajorones.atw.repository.TaskLogRepository;
import dev.themajorones.atw.service.task.TaskHandler;
import dev.themajorones.models.client.DockerClient;
import dev.themajorones.models.constants.AndroidType;
import dev.themajorones.models.constants.TaskLogConstant;
import dev.themajorones.models.dto.CreateAndroidRequest;
import dev.themajorones.models.dto.TaskCommandEnvelope;
import dev.themajorones.models.entity.Android;
import dev.themajorones.models.entity.Docker;
import dev.themajorones.models.entity.TaskLog;
import dev.themajorones.models.mapper.AndroidMapper;
import dev.themajorones.models.util.JsonUtils;
import static dev.themajorones.models.util.ValidationUtils.hasText;
import static dev.themajorones.models.util.ValidationUtils.requireId;
import static dev.themajorones.models.util.ValidationUtils.requireText;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class CreateAndroidTaskHandler implements TaskHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CreateAndroidTaskHandler.class);

    private static final Duration PORT_CHECK_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration IMAGE_PULL_TIMEOUT = Duration.ofMinutes(2);
    private static final long START_TIMEOUT_MILLIS = Duration.ofMinutes(3).toMillis();
    private static final long POLL_INTERVAL_MILLIS = Duration.ofSeconds(3).toMillis();

    private final TaskLogRepository taskLogRepository;
    private final AndroidRepository androidRepository;
    private final DockerRepository dockerRepository;
    private final DockerClient dockerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String type) {
        return TaskLogConstant.Type.CREATE_ANDROID.equals(type);
    }

    @Override
    public void handle(TaskCommandEnvelope command) {
        TaskLog taskLog = taskLogRepository.findById(command.getTaskLogId()).orElseThrow(() -> new IllegalArgumentException("Task log not found"));
        taskLog.setStatus(TaskLogConstant.Status.RUNNING).setStartedAt(System.currentTimeMillis()).setEndedAt(null).setResult(null);
        taskLogRepository.save(taskLog);

        LOG.info("Received CreateAndroid task taskLogId={}", taskLog.getId());

        Docker docker = null;
        String containerId = null;
        Android android;
        try {
            JsonNode content = objectMapper.readTree(taskLog.getContent());
            if (content instanceof ObjectNode objectNode) {
                objectNode.remove("androidId");
            }
            CreateAndroidRequest request = normalizeAndroidRequest(objectMapper.treeToValue(content, CreateAndroidRequest.class));
            boolean updatingExistingRecord = content.hasNonNull("androidId");
            Integer existingAndroidId = updatingExistingRecord ? content.path("androidId").intValue(0) : null;
            if (updatingExistingRecord) {
                LOG.info("Loading Android record androidId={} taskLogId={}", existingAndroidId, taskLog.getId());
                android = androidRepository.findById(existingAndroidId).orElseThrow(() -> new IllegalArgumentException("Android not found"));
                if (!AndroidType.REDROID.name().equalsIgnoreCase(android.getType())) {
                    throw new IllegalArgumentException("ATW only supports Redroid Android tasks");
                }
            } else {
                android = null;
            }

            docker = dockerRepository.findById(requireId(request.getDockerId(), "Docker connection id"))
                .orElseThrow(() -> new IllegalArgumentException("Docker connection not found"));

            LOG.info("Checking Android image image={} dockerId={} taskLogId={}", request.getImage(), docker.getId(), taskLog.getId());
            if (!dockerClient.imageExists(docker.getBaseUrl(), request.getImage())) {
                LOG.info("Pulling Android image image={} dockerId={} taskLogId={}", request.getImage(), docker.getId(), taskLog.getId());
                dockerClient.pullImage(docker.getBaseUrl(), request.getImage());
                waitForImageAvailability(docker, request.getImage());
            }

            String containerKey = updatingExistingRecord ? String.valueOf(existingAndroidId) : String.valueOf(taskLog.getId());
            LOG.info("Creating Android container for containerKey={} on dockerId={}", containerKey, docker.getId());
            containerId = dockerClient.createAndroidContainer(docker.getBaseUrl(), containerKey, request);

            LOG.info("Starting Android container for containerKey={} with containerId={}", containerKey, containerId);
            dockerClient.startContainer(docker.getBaseUrl(), containerId);

            Integer adbPort = waitForRunningContainer(docker, containerId);
            String adbHost = dockerClient.hostFromBaseUrl(docker.getBaseUrl());
            LOG.info("Android container is running for containerKey={} with adbHost={} and adbPort={}", containerKey, adbHost, adbPort);

            if (updatingExistingRecord) {
                android.setDocker(docker)
                    .setType(AndroidType.REDROID.name())
                    .setName(requireText(request.getName(), "Android name"))
                    .setImage(request.getImage())
                    .setContainerId(containerId)
                    .setContainerName("tmos-android-" + containerKey)
                    .setAdbHost(adbHost)
                    .setAdbPort(adbPort);
                if (android.getDetails() == null) {
                    android.setDetails(new dev.themajorones.models.entity.AndroidDetails());
                }
                android.getDetails()
                    .setAccelerationMode(request.getAccelerationMode())
                    .setWidth(request.getWidth())
                    .setHeight(request.getHeight())
                    .setDpi(request.getDpi());
                androidRepository.save(AndroidMapper.toRecord(android));
            } else {
                android = androidRepository.save(AndroidMapper.fromRequest(request, docker)
                    .setContainerId(containerId)
                    .setContainerName("tmos-android-" + containerKey)
                    .setAdbHost(adbHost)
                    .setAdbPort(adbPort));
            }

            taskLog.setStatus(TaskLogConstant.Status.SUCCESS).setEndedAt(System.currentTimeMillis()).setResult(JsonUtils.writeJson(objectMapper, Map.of(
                "status", "OK",
                "androidId", android.getId(),
                "containerId", containerId,
                "adbHost", adbHost,
                "adbPort", adbPort
            ), "Unable to serialize task result"));
            taskLogRepository.save(taskLog);
            LOG.info("Completed CreateAndroid task taskLogId={} androidId={} result=OK", taskLog.getId(), android.getId());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Failed to create Android for taskLogId={}", taskLog.getId(), ex);
            cleanupCreatedContainer(docker, containerId, taskLog.getId());
            taskLog.setStatus(TaskLogConstant.Status.FAILED).setResult(JsonUtils.writeJson(objectMapper, errorResult(ex), "Unable to serialize task result")).setEndedAt(System.currentTimeMillis());
            taskLogRepository.save(taskLog);
            throw new IllegalStateException("Android creation failed", ex);
        }
    }

    private CreateAndroidRequest normalizeAndroidRequest(CreateAndroidRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Android request is required");
        }

        if (!hasText(request.getType())) {
            request.setType(AndroidType.REDROID.name());
        }
        request.setType(request.getType().trim().toUpperCase());
        if (!AndroidType.REDROID.name().equals(request.getType())) {
            throw new IllegalArgumentException("Android type must be REDROID for worker tasks");
        }

        request.setName(requireText(request.getName(), "Android name"));
        request.setImage(requireText(request.getImage(), "Android image"));
        request.setAccelerationMode(requireText(request.getAccelerationMode(), "Android acceleration mode"));
        request.setAccelerationMode(request.getAccelerationMode().trim().toUpperCase());
        request.setDockerId(requireId(request.getDockerId(), "Docker connection id"));
        return request;
    }

    private Integer waitForRunningContainer(Docker docker, String containerId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS;
        Integer mappedPort;
        while (System.currentTimeMillis() < deadline) {
            if (dockerClient.isContainerRunning(docker.getBaseUrl(), containerId)) {
                mappedPort = dockerClient.mappedAdbPort(docker.getBaseUrl(), containerId);
                String host = dockerClient.hostFromBaseUrl(docker.getBaseUrl());
                if (mappedPort != null && dockerClient.isTcpPortReachable(host, mappedPort, PORT_CHECK_TIMEOUT)) {
                    return mappedPort;
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("Timed out waiting for Android container port");
    }

    private void waitForImageAvailability(Docker docker, String image) throws InterruptedException {
        long deadline = System.currentTimeMillis() + IMAGE_PULL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (dockerClient.imageExists(docker.getBaseUrl(), image)) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("Timed out waiting for Android image to be available");
    }

    private void cleanupCreatedContainer(Docker docker, String containerId, Integer taskLogId) {
        if (docker == null || !hasText(containerId)) {
            return;
        }
        try {
            dockerClient.inspectContainerJson(docker.getBaseUrl(), containerId);
            LOG.info("Removing failed Android container containerId={} dockerId={} taskLogId={}", containerId, docker.getId(), taskLogId);
            dockerClient.removeContainer(docker.getBaseUrl(), containerId);
        } catch (Exception cleanupEx) {
            LOG.warn("Unable to remove failed Android container containerId={} dockerId={} taskLogId={}",
                containerId, docker.getId(), taskLogId, cleanupEx);
        }
    }

    private Map<String, Object> errorResult(Exception ex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ERROR");
        result.put("error", ex.getMessage());
        result.put("exception", ex.getClass().getName());
        return result;
    }
}
