package dev.themajorones.atw.service.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.themajorones.atw.repository.AndroidRepository;
import dev.themajorones.atw.repository.ArtifactRepository;
import dev.themajorones.atw.repository.TaskLogRepository;
import dev.themajorones.atw.service.storage.ArtifactStorageClient;
import dev.themajorones.atw.service.storage.ArtifactStorageObject;
import dev.themajorones.atw.service.task.TaskHandler;
import dev.themajorones.models.client.AdbClient;
import dev.themajorones.models.constants.TaskLogConstant;
import dev.themajorones.models.dto.InstallApkRequest;
import dev.themajorones.models.dto.TaskCommandEnvelope;
import dev.themajorones.models.entity.Android;
import dev.themajorones.models.entity.Artifact;
import dev.themajorones.models.entity.TaskLog;
import dev.themajorones.models.util.JsonUtils;
import dev.themajorones.models.util.ValidationUtils;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class InstallApkTaskHandler implements TaskHandler {

    private static final Logger LOG = LoggerFactory.getLogger(InstallApkTaskHandler.class);

    private final TaskLogRepository taskLogRepository;
    private final ArtifactRepository artifactRepository;
    private final AndroidRepository androidRepository;
    private final ArtifactStorageClient artifactStorageClient;
    private final AdbClient adbClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String type) {
        return TaskLogConstant.Type.INSTALL_APK.equals(type);
    }

    @Override
    public void handle(TaskCommandEnvelope command) {
        TaskLog taskLog = taskLogRepository.findById(command.getTaskLogId())
            .orElseThrow(() -> new IllegalArgumentException("Task log not found"));
        taskLog.setStatus(TaskLogConstant.Status.RUNNING).setStartedAt(System.currentTimeMillis()).setEndedAt(null).setResult(null);
        taskLogRepository.save(taskLog);

        Path tempFile = null;
        try {
            InstallApkRequest request = objectMapper.readValue(taskLog.getContent(), InstallApkRequest.class);
            Artifact artifact = artifactRepository.findById(ValidationUtils.requireId(request.getArtifactId(), "Artifact id"))
                .orElseThrow(() -> new IllegalArgumentException("Artifact was not found"));
            Android android = androidRepository.findById(ValidationUtils.requireId(request.getAndroidId(), "Android id"))
                .orElseThrow(() -> new IllegalArgumentException("Android was not found"));

            if (!ValidationUtils.hasText(artifact.getStorageKey())) {
                throw new IllegalStateException("Artifact file is not available");
            }
            if (!ValidationUtils.hasText(android.getAdbHost()) || android.getAdbPort() == null) {
                throw new IllegalStateException("Android device does not have an ADB address");
            }

            tempFile = Files.createTempFile("artifact-install-", ".apk");
            try (ArtifactStorageObject stored = artifactStorageClient.getObject(artifact.getStorageKey());
                 var inputStream = stored.inputStream();
                 var outputStream = Files.newOutputStream(tempFile)) {
                inputStream.transferTo(outputStream);
            }

            String serial = android.getAdbHost() + ":" + android.getAdbPort();
            LOG.info("Connecting to Android serial={} taskLogId={}", serial, taskLog.getId());
            adbClient.connect(android.getAdbHost(), android.getAdbPort());
            LOG.info("Installing APK artifactId={} androidId={} taskLogId={}", artifact.getId(), android.getId(), taskLog.getId());
            var installResult = adbClient.install(serial, tempFile);

            taskLog.setStatus(TaskLogConstant.Status.SUCCESS).setEndedAt(System.currentTimeMillis()).setResult(JsonUtils.writeJson(objectMapper, Map.of(
                "status", "OK",
                "artifactId", artifact.getId(),
                "androidId", android.getId(),
                "serial", serial,
                "stdout", installResult.getStdout(),
                "stderr", installResult.getStderr(),
                "durationMillis", installResult.getDuration() == null ? null : installResult.getDuration().toMillis()
            ), "Unable to serialize task result"));
            taskLogRepository.save(taskLog);
            LOG.info("Completed install APK task taskLogId={} artifactId={} androidId={}", taskLog.getId(), artifact.getId(), android.getId());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Failed to install APK for taskLogId={}", taskLog.getId(), ex);
            taskLog.setStatus(TaskLogConstant.Status.FAILED).setEndedAt(System.currentTimeMillis()).setResult(JsonUtils.writeJson(objectMapper, errorResult(ex), "Unable to serialize task result"));
            taskLogRepository.save(taskLog);
            throw new IllegalStateException("APK install failed", ex);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ex) {
                    LOG.warn("Unable to delete APK temp file taskLogId={} path={}", taskLog.getId(), tempFile, ex);
                }
            }
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
