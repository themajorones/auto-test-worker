package dev.themajorones.atw.service.handler;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.themajorones.atw.repository.AndroidRepository;
import dev.themajorones.atw.repository.DockerRepository;
import dev.themajorones.atw.repository.TaskLogRepository;
import dev.themajorones.atw.service.task.TaskHandler;
import dev.themajorones.models.client.DockerClient;
import dev.themajorones.models.constants.AndroidType;
import dev.themajorones.models.constants.TaskLogConstant;
import dev.themajorones.models.dto.TaskCommandEnvelope;
import dev.themajorones.models.entity.Android;
import dev.themajorones.models.entity.Docker;
import dev.themajorones.models.entity.TaskLog;
import dev.themajorones.models.mapper.AndroidMapper;
import dev.themajorones.models.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CreateAndroidTaskHandler implements TaskHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CreateAndroidTaskHandler.class);

    private static final Duration PORT_CHECK_TIMEOUT = Duration.ofSeconds(2);
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
    @Transactional
    public void handle(TaskCommandEnvelope command) {
        TaskLog taskLog = taskLogRepository.findById(command.getTaskLogId()).orElseThrow(() -> new IllegalArgumentException("Task log not found"));
        taskLog.setStatus(TaskLogConstant.Status.RUNNING).setStartedAt(System.currentTimeMillis()).setEndedAt(null).setResult(null);
        taskLogRepository.save(taskLog);

        LOG.info("Received CreateAndroid task taskLogId={}", taskLog.getId());

        Android android;
        try {
            JsonNode content = objectMapper.readTree(taskLog.getContent());
            Integer androidId = content.path("androidId").intValue(0);
            LOG.info("Loading Android record androidId={} taskLogId={}", androidId, taskLog.getId());
            android = androidRepository.findById(androidId).orElseThrow(() -> new IllegalArgumentException("Android not found"));
            if (!AndroidType.REDROID.name().equalsIgnoreCase(android.getType())) {
                throw new IllegalArgumentException("ATW only supports Redroid Android tasks");
            }
            Docker docker = dockerRepository.findById(android.getDocker().getId()).orElseThrow(() -> new IllegalArgumentException("Docker connection not found"));

            LOG.info("Checking Android image image={} dockerId={} taskLogId={}", android.getImage(), docker.getId(), taskLog.getId());
            if (!dockerClient.imageExists(docker.getBaseUrl(), android.getImage())) {
                LOG.info("Pulling Android image image={} dockerId={} taskLogId={}", android.getImage(), docker.getId(), taskLog.getId());
                dockerClient.pullImage(docker.getBaseUrl(), android.getImage());
            }

            LOG.info("Creating Android container for androidId={} on dockerId={}", android.getId(), docker.getId());
            String containerId = dockerClient.createAndroidContainer(docker.getBaseUrl(), android.getId(), android);

            LOG.info("Starting Android container for androidId={} with containerId={}", android.getId(), containerId);
            dockerClient.startContainer(docker.getBaseUrl(), containerId);

            Integer adbPort = waitForRunningContainer(docker, containerId);
            String adbHost = dockerClient.hostFromBaseUrl(docker.getBaseUrl());
            LOG.info("Android container is running for androidId={} with adbHost={} and adbPort={}", android.getId(), adbHost, adbPort);

            android.setContainerId(containerId).setContainerName("tmos-android-" + android.getId()).setAdbHost(adbHost).setAdbPort(adbPort);
            androidRepository.save(AndroidMapper.toRecord(android));

            taskLog.setStatus(TaskLogConstant.Status.SUCCESS).setEndedAt(System.currentTimeMillis()).setResult(JsonUtils.writeJson(objectMapper, Map.of(
                "status", "OK",
                "androidId", android.getId(),
                "containerId", containerId,
                "adbHost", adbHost,
                "adbPort", adbPort
            ), "Unable to serialize task result"));
            taskLogRepository.save(taskLog);
            LOG.info("Completed CreateAndroid task taskLogId={} androidId={} result=OK", taskLog.getId(), android.getId());
        } catch (IllegalArgumentException | InterruptedException | JacksonException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Failed to create Android for taskLogId={}", taskLog.getId(), ex);
            taskLog.setStatus(TaskLogConstant.Status.FAILED).setResult(JsonUtils.writeJson(objectMapper, errorResult(ex), "Unable to serialize task result")).setEndedAt(System.currentTimeMillis());
            taskLogRepository.save(taskLog);
            throw new IllegalStateException("Android creation failed", ex);
        }
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

    private Map<String, Object> errorResult(Exception ex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ERROR");
        result.put("error", ex.getMessage());
        result.put("exception", ex.getClass().getName());
        return result;
    }
}
