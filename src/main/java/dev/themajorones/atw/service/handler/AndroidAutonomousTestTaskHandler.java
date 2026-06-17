package dev.themajorones.atw.service.handler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import dev.themajorones.atw.repository.AndroidRepository;
import dev.themajorones.atw.repository.AndroidTestStepHistoryRepository;
import dev.themajorones.atw.repository.ArtifactRepository;
import dev.themajorones.atw.repository.OllamaRepository;
import dev.themajorones.atw.repository.TaskLogRepository;
import dev.themajorones.atw.service.storage.ArtifactStorageClient;
import dev.themajorones.atw.service.storage.ArtifactStorageObject;
import dev.themajorones.atw.service.storage.ImageStorageClient;
import dev.themajorones.atw.service.task.TaskHandler;
import dev.themajorones.atw.service.vision.VisionAnalyzer;
import dev.themajorones.atw.service.vision.VisionResult;
import dev.themajorones.models.client.AdbClient;
import dev.themajorones.models.client.OllamaClient;
import dev.themajorones.models.constants.RabbitMqConstant;
import dev.themajorones.models.constants.TaskLogConstant;
import dev.themajorones.models.constants.TaskProgressConstant;
import dev.themajorones.models.dto.AndroidTestCoordinate;
import dev.themajorones.models.dto.AndroidTestDecision;
import dev.themajorones.models.dto.AndroidTestRunResult;
import dev.themajorones.models.dto.AndroidTestStepResult;
import dev.themajorones.models.dto.RunAndroidTestRequest;
import dev.themajorones.models.dto.TaskProgressEvent;
import dev.themajorones.models.dto.TaskCommandEnvelope;
import dev.themajorones.models.entity.Android;
import dev.themajorones.models.entity.AndroidTestStepHistory;
import dev.themajorones.models.entity.Artifact;
import dev.themajorones.models.entity.Ollama;
import dev.themajorones.models.entity.TaskLog;
import dev.themajorones.models.util.JsonUtils;
import dev.themajorones.models.util.ValidationUtils;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AndroidAutonomousTestTaskHandler implements TaskHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AndroidAutonomousTestTaskHandler.class);
    private static final int DEFAULT_MAX_STEPS = 20;
    private static final int STAGNATION_LIMIT = 3;
    private static final Duration DEFAULT_SWIPE_DURATION = Duration.ofMillis(400);
    private static final Duration POST_ACTION_DELAY = Duration.ofSeconds(1);
    private static final Pattern UI_CENTER = Pattern.compile("center=\\((\\d+),(\\d+)\\)");
    private static final Pattern UI_TEXT = Pattern.compile("text=\"([^\"]*)\"");
    private static final Pattern UI_DESC = Pattern.compile("desc=\"([^\"]*)\"");
    private static final List<String> FALLBACK_CLICK_LABELS = List.of(
        "allow",
        "while using the app",
        "only this time",
        "ok",
        "continue",
        "yes",
        "next",
        "start"
    );

    private final TaskLogRepository taskLogRepository;
    private final ArtifactRepository artifactRepository;
    private final AndroidRepository androidRepository;
    private final OllamaRepository ollamaRepository;
    private final AndroidTestStepHistoryRepository androidTestStepHistoryRepository;
    private final ArtifactStorageClient artifactStorageClient;
    private final ImageStorageClient imageStorageClient;
    private final RabbitOperations rabbitOperations;
    private final AdbClient adbClient;
    private final OllamaClient ollamaClient;
    private final ApkPackageNameExtractor apkPackageNameExtractor;
    private final UiHierarchyCompactor uiHierarchyCompactor;
    private final VisionAnalyzer visionAnalyzer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String type) {
        return TaskLogConstant.Type.ANDROID_AUTONOMOUS_TEST.equals(type);
    }

    @Override
    public void handle(TaskCommandEnvelope command) {
        TaskLog taskLog = taskLogRepository.findById(command.getTaskLogId())
            .orElseThrow(() -> new IllegalArgumentException("Task log not found"));
        LOG.info("Starting Android autonomous test taskLogId={} envelopeType={}", taskLog.getId(), command.getType());
        taskLog.setStatus(TaskLogConstant.Status.RUNNING).setStartedAt(System.currentTimeMillis()).setEndedAt(null).setResult(null);
        taskLogRepository.save(taskLog);
        publishTaskLog(taskLog, TaskProgressConstant.EventType.TASK_LOG_UPSERTED);
        androidTestStepHistoryRepository.deleteAllByTaskLogId(taskLog.getId());
        imageStorageClient.ensureBucketExists();

        AndroidTestRunResult result = new AndroidTestRunResult().setStatus("RUNNING").setState("IN_PROGRESS");
        Path tempApk = null;
        Path lastScreenshot = null;
        try {
            RunAndroidTestRequest request = objectMapper.readValue(taskLog.getContent(), RunAndroidTestRequest.class);
            Artifact artifact = artifactRepository.findById(ValidationUtils.requireId(request.getArtifactId(), "Artifact id"))
                .orElseThrow(() -> new IllegalArgumentException("Artifact was not found"));
            Android android = androidRepository.findById(ValidationUtils.requireId(request.getAndroidId(), "Android id"))
                .orElseThrow(() -> new IllegalArgumentException("Android device was not found"));
            Ollama ollama = ollamaRepository.findById(ValidationUtils.requireId(request.getOllamaId(), "Ollama connection id"))
                .orElseThrow(() -> new IllegalArgumentException("Ollama connection was not found"));
            int maxSteps = normalizeMaxSteps(request.getMaxSteps());
            String objective = ValidationUtils.requireText(request.getObjective(), "Test objective");
            String serial = androidSerial(android);
            LOG.info(
                "Loaded Android test inputs taskLogId={} artifactId={} androidId={} ollamaId={} serial={} maxSteps={} objectiveLength={}",
                taskLog.getId(),
                artifact.getId(),
                android.getId(),
                ollama.getId(),
                serial,
                maxSteps,
                objective.length()
            );

            result
                .setArtifactId(artifact.getId())
                .setAndroidId(android.getId())
                .setOllamaId(ollama.getId())
                .setSerial(serial)
                .setMaxSteps(maxSteps);
            writeResult(taskLog, result);

            LOG.info("Downloading APK for Android test taskLogId={} artifactId={} storageKey={}", taskLog.getId(), artifact.getId(), artifact.getStorageKey());
            tempApk = downloadApk(artifact);
            LOG.info("Downloaded APK for Android test taskLogId={} path={} sizeBytes={}", taskLog.getId(), tempApk, Files.size(tempApk));
            String packageName = apkPackageNameExtractor.extractPackageName(tempApk);
            LOG.info("Inferred APK package for Android test taskLogId={} packageName={}", taskLog.getId(), packageName);
            result.setPackageName(packageName);
            writeResult(taskLog, result);

            LOG.info("Connecting ADB for Android test taskLogId={} serial={}", taskLog.getId(), serial);
            adbClient.connect(android.getAdbHost(), android.getAdbPort());
            LOG.info("Installing APK for Android test taskLogId={} serial={} packageName={}", taskLog.getId(), serial, packageName);
            adbClient.install(serial, tempApk);
            LOG.info("Opening app for Android test taskLogId={} serial={} packageName={}", taskLog.getId(), serial, packageName);
            adbClient.openApp(serial, packageName);
            sleep(POST_ACTION_DELAY);

            String previousHash = null;
            int repeatedHashCount = 0;
            AndroidTestDecision finalDecision = null;
            for (int stepNumber = 1; stepNumber <= maxSteps; stepNumber++) {
                LOG.info("Android test step started taskLogId={} step={} maxSteps={}", taskLog.getId(), stepNumber, maxSteps);
                AndroidTestStepResult step = new AndroidTestStepResult()
                    .setStep(stepNumber)
                    .setStartedAt(System.currentTimeMillis());
                result.getSteps().add(step);
                boolean historySaved = false;
                try {
                    LOG.debug("Dumping UI hierarchy taskLogId={} step={} serial={}", taskLog.getId(), stepNumber, serial);
                    String xml = adbClient.dumpUiHierarchy(serial);
                    String uiContext = uiHierarchyCompactor.compact(xml);
                    String uiHash = uiHierarchyCompactor.hash(uiContext);
                    if (uiHash.equals(previousHash)) {
                        repeatedHashCount++;
                    } else {
                        previousHash = uiHash;
                        repeatedHashCount = 1;
                    }
                    LOG.info(
                        "UI context captured taskLogId={} step={} uiHash={} repeatedHashCount={} compactLength={}",
                        taskLog.getId(),
                        stepNumber,
                        uiHash,
                        repeatedHashCount,
                        uiContext.length()
                    );

                    deleteIfExists(lastScreenshot);
                    lastScreenshot = Files.createTempFile("android-test-step-", ".png");
                    LOG.debug("Capturing screenshot taskLogId={} step={} path={}", taskLog.getId(), stepNumber, lastScreenshot);
                    adbClient.screenshot(serial, lastScreenshot);
                    LOG.info("Screenshot captured taskLogId={} step={} sizeBytes={}", taskLog.getId(), stepNumber, Files.size(lastScreenshot));
                    VisionResult vision = visionAnalyzer.analyze(lastScreenshot, objective, uiContext);
                    LOG.info(
                        "Vision annotations captured taskLogId={} step={} provider={} annotationLength={}",
                        taskLog.getId(),
                        stepNumber,
                        vision.provider(),
                        vision.text() == null ? 0 : vision.text().length()
                    );
                    AndroidTestDecision decision = decide(ollama, objective, result, stepNumber, uiContext, vision);
                    step
                        .setForeground(safeForeground(serial))
                        .setUiHash(uiHash)
                        .setUiContext(uiContext)
                        .setVisionProvider(vision.provider())
                        .setVision(vision.text())
                        .setDecision(decision);
                    inferMissingDecisionTarget(decision, uiContext, objective);
                    validateDecision(decision);
                    finalDecision = decision;
                    LOG.info(
                        "Ollama decision taskLogId={} step={} action={} state={} hasTarget={} hasSwipe={} inputTextLength={} reasoningLength={}",
                        taskLog.getId(),
                        stepNumber,
                        decision.getAction(),
                        decision.getState(),
                        decision.getTarget() != null,
                        decision.getSwipe() != null,
                        decision.getInputText() == null ? 0 : decision.getInputText().length(),
                        decision.getReasoning() == null ? 0 : decision.getReasoning().length()
                    );

                    if ("SUCCESS".equals(decision.getState())) {
                        LOG.info("Android test succeeded by model decision taskLogId={} step={}", taskLog.getId(), stepNumber);
                        completeStep(step, "Goal satisfied");
                        captureAndAttachStepImage(taskLog.getId(), step, serial);
                        finish(result, taskLog, TaskLogConstant.Status.SUCCESS, "SUCCESS", "Model reported success", step.getImageStorageKey());
                        return;
                    }
                    if ("UNREACHABLE".equals(decision.getState())) {
                        LOG.info("Android test unreachable by model decision taskLogId={} step={}", taskLog.getId(), stepNumber);
                        completeStep(step, "Goal unreachable");
                        captureAndAttachStepImage(taskLog.getId(), step, serial);
                        finish(result, taskLog, TaskLogConstant.Status.FAILED, "UNREACHABLE", "Model reported unreachable", step.getImageStorageKey());
                        return;
                    }
                    if ("TERMINATE".equals(decision.getAction())) {
                        LOG.info("Android test terminated by model action taskLogId={} step={} state={}", taskLog.getId(), stepNumber, decision.getState());
                        completeStep(step, "Model terminated without success");
                        captureAndAttachStepImage(taskLog.getId(), step, serial);
                        finish(result, taskLog, TaskLogConstant.Status.FAILED, "UNREACHABLE", "Model terminated without success", step.getImageStorageKey());
                        return;
                    }

                    LOG.info("Executing Android test action taskLogId={} step={} action={}", taskLog.getId(), stepNumber, decision.getAction());
                    String actionResult = executeDecision(serial, decision);
                    LOG.info("Android test action completed taskLogId={} step={} action={} result={}", taskLog.getId(), stepNumber, decision.getAction(), actionResult);
                    completeStep(step, actionResult);
                    result.setCompletedSteps(stepNumber);
                    sleep(POST_ACTION_DELAY);
                    captureAndAttachStepImage(taskLog.getId(), step, serial);
                    result.setFinalScreenshotStorageKey(step.getImageStorageKey());
                    writeResult(taskLog, result);

                    if (repeatedHashCount >= STAGNATION_LIMIT) {
                        LOG.info("Android test stagnated taskLogId={} step={} uiHash={} repeatedHashCount={}", taskLog.getId(), stepNumber, uiHash, repeatedHashCount);
                        finish(result, taskLog, TaskLogConstant.Status.FAILED, "STAGNATED", "UI hierarchy did not change for three consecutive steps", step.getImageStorageKey());
                        return;
                    }
                } catch (Exception stepEx) {
                    LOG.error("Android test step failed taskLogId={} step={}", taskLog.getId(), stepNumber, stepEx);
                    String failureMessage = stepEx.getMessage();
                    if (!StringUtils.hasText(step.getActionResult())) {
                        step.setActionResult("ERROR: " + nullToEmpty(failureMessage));
                    } else {
                        step.setActionResult(step.getActionResult() + " | ERROR: " + nullToEmpty(failureMessage));
                    }
                    step.setError(failureMessage).setEndedAt(System.currentTimeMillis());
                    try {
                        captureAndAttachStepImage(taskLog.getId(), step, serial);
                    } catch (Exception screenshotEx) {
                        LOG.warn("Unable to capture failure screenshot taskLogId={} step={}", taskLog.getId(), stepNumber, screenshotEx);
                        step.setError(nullToEmpty(step.getError()) + "\nScreenshot capture failed: " + screenshotEx.getMessage());
                    }
                    throw stepEx;
                } finally {
                    if (step.getEndedAt() == null) {
                        step.setEndedAt(System.currentTimeMillis());
                    }
                    if (!historySaved) {
                        persistStepHistory(taskLog, step);
                        historySaved = true;
                    }
                    LOG.info("Android test step finished taskLogId={} step={} durationMillis={}", taskLog.getId(), stepNumber, step.getEndedAt() - step.getStartedAt());
                    writeResult(taskLog, result);
                }
            }

            String reason = finalDecision == null ? "Step ceiling reached before a decision was made" : "Step ceiling reached";
            LOG.info("Android test reached max steps taskLogId={} maxSteps={}", taskLog.getId(), maxSteps);
            finish(result, taskLog, TaskLogConstant.Status.FAILED, "MAX_STEPS", reason, result.getFinalScreenshotStorageKey());
        } catch (Exception ex) {
            LOG.error("Android autonomous test failed taskLogId={}", taskLog.getId(), ex);
            result.setStatus("ERROR").setState("ERROR").setReason(ex.getMessage()).setCompletedSteps(result.getSteps().size());
            taskLog.setStatus(TaskLogConstant.Status.FAILED).setEndedAt(System.currentTimeMillis()).setResult(JsonUtils.writeJson(objectMapper, compactResult(result), "Unable to serialize task result"));
            taskLogRepository.save(taskLog);
            publishTaskLog(taskLog, TaskProgressConstant.EventType.TASK_LOG_UPSERTED);
            throw new IllegalStateException("Android autonomous test failed", ex);
        } finally {
            deleteIfExists(tempApk);
            deleteIfExists(lastScreenshot);
        }
    }

    private Path downloadApk(Artifact artifact) throws IOException {
        if (!StringUtils.hasText(artifact.getStorageKey())) {
            throw new IllegalStateException("Artifact file is not available for testing");
        }
        Path tempFile = Files.createTempFile("android-test-artifact-", ".apk");
        try (ArtifactStorageObject stored = artifactStorageClient.getObject(artifact.getStorageKey());
             InputStream inputStream = stored.inputStream();
             var outputStream = Files.newOutputStream(tempFile)) {
            inputStream.transferTo(outputStream);
        }
        return tempFile;
    }

    private AndroidTestDecision decide(
        Ollama ollama,
        String objective,
        AndroidTestRunResult run,
        int stepNumber,
        String uiContext,
        VisionResult vision
    ) {
        String prompt = """
            You are an automated Android QA engine controlling a real Android device through ADB.
            Respond with one JSON object matching the schema. Do not include markdown.
            Required JSON shape:
            {
              "reasoning": "short reason",
              "action": "CLICK|ENTER_TEXT|SWIPE|BACK|WAIT|TERMINATE",
              "target": {"x": 0, "y": 0} or null,
              "swipe": {"x1": 0, "y1": 0, "x2": 0, "y2": 0, "durationMs": 400} or null,
              "inputText": "text to type" or null,
              "state": "IN_PROGRESS|SUCCESS|UNREACHABLE"
            }

            Goal:
            %s

            Current app package:
            %s

            Step:
            %d of %d

            Recent execution history:
            %s

            Compacted UI tree:
            %s

            Vision annotations:
            provider=%s
            %s

            Choose exactly one next action. Use CLICK/ENTER_TEXT/SWIPE/BACK/WAIT while state is IN_PROGRESS.
            Use SUCCESS only when the goal is clearly achieved. Use UNREACHABLE when the goal cannot be completed.
            Coordinates must come from UI bounds centers when possible.
            """.formatted(
                objective,
                nullToEmpty(run.getPackageName()),
                stepNumber,
                run.getMaxSteps(),
                recentHistory(run.getSteps()),
                truncate(uiContext, 12_000),
                vision.provider(),
                truncate(vision.text(), 4_000)
            );
        LOG.debug("Calling Ollama for Android test decision ollamaId={} step={} promptLength={}", ollama.getId(), stepNumber, prompt.length());
        String generated = ollamaClient.generateStructured(ollama.getBaseUrl(), ollama.getModel(), prompt, decisionSchema());
        LOG.debug("Received Ollama decision payload ollamaId={} step={} payloadLength={}", ollama.getId(), stepNumber, generated.length());
        try {
            return objectMapper.readValue(generated, AndroidTestDecision.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse Ollama decision: " + generated, ex);
        }
    }

    private Map<String, Object> decisionSchema() {
        Map<String, Object> coordinate = objectSchema(Map.of(
            "x", integerSchema(),
            "y", integerSchema()
        ), List.of("x", "y"));
        Map<String, Object> swipe = objectSchema(Map.of(
            "x1", integerSchema(),
            "y1", integerSchema(),
            "x2", integerSchema(),
            "y2", integerSchema(),
            "durationMs", integerSchema()
        ), List.of("x1", "y1", "x2", "y2", "durationMs"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reasoning", Map.of("type", "string"));
        properties.put("action", Map.of("type", "string", "enum", List.of("CLICK", "ENTER_TEXT", "SWIPE", "BACK", "WAIT", "TERMINATE")));
        properties.put("target", nullable(coordinate));
        properties.put("swipe", nullable(swipe));
        properties.put("inputText", Map.of("type", List.of("string", "null")));
        properties.put("state", Map.of("type", "string", "enum", List.of("IN_PROGRESS", "SUCCESS", "UNREACHABLE")));
        return objectSchema(properties, List.of("reasoning", "action", "target", "swipe", "inputText", "state"));
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> integerSchema() {
        return Map.of("type", "integer", "minimum", 0);
    }

    private Map<String, Object> nullable(Map<String, Object> schema) {
        Map<String, Object> nullable = new LinkedHashMap<>(schema);
        nullable.put("type", List.of("object", "null"));
        return nullable;
    }

    private void validateDecision(AndroidTestDecision decision) {
        if (decision == null) {
            throw new IllegalStateException("Ollama decision is required");
        }
        String action = normalizeUpper(decision.getAction(), "Action");
        String state = normalizeUpper(decision.getState(), "State");
        decision.setAction(action).setState(state);
        if (!List.of("CLICK", "ENTER_TEXT", "SWIPE", "BACK", "WAIT", "TERMINATE").contains(action)) {
            throw new IllegalStateException("Unsupported action: " + action);
        }
        if (!List.of("IN_PROGRESS", "SUCCESS", "UNREACHABLE").contains(state)) {
            throw new IllegalStateException("Unsupported state: " + state);
        }
        if (!"IN_PROGRESS".equals(state)) {
            return;
        }
        if ("CLICK".equals(action) && decision.getTarget() == null) {
            throw new IllegalStateException("CLICK requires target coordinates");
        }
        if ("ENTER_TEXT".equals(action) && !StringUtils.hasText(decision.getInputText())) {
            throw new IllegalStateException("ENTER_TEXT requires inputText");
        }
        if ("SWIPE".equals(action) && decision.getSwipe() == null) {
            throw new IllegalStateException("SWIPE requires swipe coordinates");
        }
    }

    private void inferMissingDecisionTarget(AndroidTestDecision decision, String uiContext, String objective) {
        if (decision == null || decision.getTarget() != null) {
            return;
        }
        String action = decision.getAction() == null ? "" : decision.getAction().trim().toUpperCase(Locale.ROOT);
        if (!"CLICK".equals(action) && !"ENTER_TEXT".equals(action)) {
            return;
        }
        UiTarget target = findFallbackTarget(uiContext, decision, objective);
        if (target == null) {
            return;
        }
        decision.setTarget(new AndroidTestCoordinate().setX(target.x()).setY(target.y()));
        String reason = nullToEmpty(decision.getReasoning());
        String suffix = "Fallback target inferred from UI node: " + target.label();
        decision.setReasoning(StringUtils.hasText(reason) ? reason + " " + suffix : suffix);
        LOG.info("Inferred missing Android test target action={} label={} x={} y={}", action, target.label(), target.x(), target.y());
    }

    private UiTarget findFallbackTarget(String uiContext, AndroidTestDecision decision, String objective) {
        if (!StringUtils.hasText(uiContext)) {
            return null;
        }
        String intent = (nullToEmpty(objective) + " " + nullToEmpty(decision.getReasoning()) + " " + nullToEmpty(decision.getInputText()))
            .toLowerCase(Locale.ROOT);
        UiTarget firstClickable = null;
        for (String line : uiContext.split("\\R")) {
            if (!line.contains("center=(")) {
                continue;
            }
            String lowered = line.toLowerCase(Locale.ROOT);
            if (!lowered.contains("clickable=true") && !lowered.contains("focusable=true")) {
                continue;
            }
            UiTarget target = parseTarget(line);
            if (target == null) {
                continue;
            }
            if (firstClickable == null) {
                firstClickable = target;
            }
            String label = target.label().toLowerCase(Locale.ROOT);
            if (FALLBACK_CLICK_LABELS.stream().anyMatch(label::contains)) {
                return target;
            }
            if (StringUtils.hasText(label) && intent.contains(label)) {
                return target;
            }
        }
        return firstClickable;
    }

    private UiTarget parseTarget(String line) {
        Matcher center = UI_CENTER.matcher(line);
        if (!center.find()) {
            return null;
        }
        String label = firstGroup(UI_TEXT.matcher(line));
        if (!StringUtils.hasText(label)) {
            label = firstGroup(UI_DESC.matcher(line));
        }
        return new UiTarget(
            Integer.parseInt(center.group(1)),
            Integer.parseInt(center.group(2)),
            StringUtils.hasText(label) ? label : line.strip()
        );
    }

    private String firstGroup(Matcher matcher) {
        return matcher.find() ? matcher.group(1) : "";
    }

    private String executeDecision(String serial, AndroidTestDecision decision) {
        return switch (decision.getAction()) {
            case "CLICK" -> {
                LOG.debug("ADB tap serial={} x={} y={}", serial, decision.getTarget().getX(), decision.getTarget().getY());
                adbClient.tap(serial, decision.getTarget().getX(), decision.getTarget().getY());
                yield "Clicked at " + decision.getTarget().getX() + "," + decision.getTarget().getY();
            }
            case "ENTER_TEXT" -> {
                if (decision.getTarget() != null) {
                    LOG.debug("ADB focus before input serial={} x={} y={}", serial, decision.getTarget().getX(), decision.getTarget().getY());
                    adbClient.tap(serial, decision.getTarget().getX(), decision.getTarget().getY());
                    sleep(Duration.ofMillis(250));
                }
                LOG.debug("ADB input text serial={} inputTextLength={}", serial, decision.getInputText().length());
                adbClient.inputText(serial, decision.getInputText());
                yield "Input text";
            }
            case "SWIPE" -> {
                var swipe = decision.getSwipe();
                LOG.debug(
                    "ADB swipe serial={} x1={} y1={} x2={} y2={} durationMs={}",
                    serial,
                    swipe.getX1(),
                    swipe.getY1(),
                    swipe.getX2(),
                    swipe.getY2(),
                    swipe.getDurationMs()
                );
                adbClient.swipe(
                    serial,
                    swipe.getX1(),
                    swipe.getY1(),
                    swipe.getX2(),
                    swipe.getY2(),
                    Duration.ofMillis(swipe.getDurationMs() == null ? DEFAULT_SWIPE_DURATION.toMillis() : swipe.getDurationMs())
                );
                yield "Swiped";
            }
            case "BACK" -> {
                LOG.debug("ADB back serial={}", serial);
                adbClient.press(serial, 4);
                yield "Pressed back";
            }
            case "WAIT" -> {
                LOG.debug("ADB wait serial={} durationMillis={}", serial, POST_ACTION_DELAY.toMillis());
                sleep(POST_ACTION_DELAY);
                yield "Waited";
            }
            default -> "No action";
        };
    }

    private void finish(
        AndroidTestRunResult result,
        TaskLog taskLog,
        String taskStatus,
        String state,
        String reason,
        String finalScreenshotStorageKey
    ) {
        result.setStatus(taskStatus).setState(state).setReason(reason).setCompletedSteps(result.getSteps().size());
        if (StringUtils.hasText(finalScreenshotStorageKey)) {
            result.setFinalScreenshotStorageKey(finalScreenshotStorageKey);
        }
        LOG.info(
            "Finishing Android test taskLogId={} taskStatus={} state={} reason={} completedSteps={} finalScreenshotKey={}",
            taskLog.getId(),
            taskStatus,
            state,
            reason,
            result.getCompletedSteps(),
            result.getFinalScreenshotStorageKey()
        );
        taskLog.setStatus(taskStatus).setEndedAt(System.currentTimeMillis()).setResult(JsonUtils.writeJson(objectMapper, compactResult(result), "Unable to serialize task result"));
        taskLogRepository.save(taskLog);
        publishTaskLog(taskLog, TaskProgressConstant.EventType.TASK_LOG_UPSERTED);
    }

    private void completeStep(AndroidTestStepResult step, String actionResult) {
        step.setActionResult(actionResult).setEndedAt(System.currentTimeMillis());
    }

    private void writeResult(TaskLog taskLog, AndroidTestRunResult result) {
        taskLog.setResult(JsonUtils.writeJson(objectMapper, compactResult(result), "Unable to serialize task result"));
        taskLogRepository.save(taskLog);
        publishTaskLog(taskLog, TaskProgressConstant.EventType.TASK_LOG_UPSERTED);
    }

    private Map<String, Object> compactResult(AndroidTestRunResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result.getStatus());
        summary.put("state", result.getState());
        summary.put("reason", result.getReason());
        summary.put("artifactId", result.getArtifactId());
        summary.put("androidId", result.getAndroidId());
        summary.put("ollamaId", result.getOllamaId());
        summary.put("packageName", result.getPackageName());
        summary.put("serial", result.getSerial());
        summary.put("maxSteps", result.getMaxSteps());
        summary.put("completedSteps", result.getCompletedSteps());
        summary.put("finalScreenshotStorageKey", result.getFinalScreenshotStorageKey());
        return summary;
    }

    private void captureAndAttachStepImage(Integer taskLogId, AndroidTestStepResult step, String serial) throws IOException {
        Path screenshot = Files.createTempFile("android-test-post-step-", ".png");
        try {
            LOG.debug("Capturing post-step screenshot taskLogId={} step={} path={}", taskLogId, step.getStep(), screenshot);
            adbClient.screenshot(serial, screenshot);
            String key = "android-tests/" + taskLogId + "/steps/" + step.getStep() + ".png";
            long size = Files.size(screenshot);
            try (InputStream inputStream = Files.newInputStream(screenshot)) {
                LOG.info("Uploading Android test step screenshot taskLogId={} step={} key={} sizeBytes={}", taskLogId, step.getStep(), key, size);
                imageStorageClient.putObject(key, inputStream, size, "image/png");
            }
            step.setImageStorageKey(key);
        } finally {
            deleteIfExists(screenshot);
        }
    }

    private void persistStepHistory(TaskLog taskLog, AndroidTestStepResult step) {
        AndroidTestDecision decision = step.getDecision();
        AndroidTestStepHistory history = new AndroidTestStepHistory()
            .setTaskLogId(taskLog.getId())
            .setStepNumber(step.getStep())
            .setStartedAt(step.getStartedAt())
            .setEndedAt(step.getEndedAt())
            .setForeground(step.getForeground())
            .setUiHash(step.getUiHash())
            .setUiContext(step.getUiContext())
            .setVisionProvider(step.getVisionProvider())
            .setVisionText(step.getVision())
            .setAction(decision == null ? null : decision.getAction())
            .setState(decision == null ? null : decision.getState())
            .setInputText(decision == null ? null : decision.getInputText())
            .setReasoning(decision == null ? null : decision.getReasoning())
            .setDecisionJson(decision == null ? null : JsonUtils.writeJson(objectMapper, decision, "Unable to serialize Android test decision"))
            .setActionResult(step.getActionResult())
            .setError(step.getError())
            .setImageStorageKey(step.getImageStorageKey());
        if (decision != null && decision.getTarget() != null) {
            history
                .setTargetX(decision.getTarget().getX())
                .setTargetY(decision.getTarget().getY());
        }
        if (decision != null && decision.getSwipe() != null) {
            history
                .setSwipeX1(decision.getSwipe().getX1())
                .setSwipeY1(decision.getSwipe().getY1())
                .setSwipeX2(decision.getSwipe().getX2())
                .setSwipeY2(decision.getSwipe().getY2())
                .setSwipeDurationMs(decision.getSwipe().getDurationMs());
        }
        androidTestStepHistoryRepository.save(history);
        publishStep(taskLog, history, TaskProgressConstant.EventType.STEP_UPSERTED);
        LOG.info("Saved Android test step history taskLogId={} step={} imageKey={}", taskLog.getId(), step.getStep(), step.getImageStorageKey());
    }

    private String recentHistory(List<AndroidTestStepResult> steps) {
        List<String> history = new ArrayList<>();
        int start = Math.max(0, steps.size() - 5);
        for (int i = start; i < steps.size(); i++) {
            AndroidTestStepResult step = steps.get(i);
            if (step.getDecision() == null) {
                continue;
            }
            history.add("step " + step.getStep() + ": "
                + step.getDecision().getAction()
                + " state=" + step.getDecision().getState()
                + " result=" + nullToEmpty(step.getActionResult())
                + " reason=" + nullToEmpty(step.getDecision().getReasoning()));
        }
        return history.isEmpty() ? "none" : String.join("\n", history);
    }

    private String safeForeground(String serial) {
        try {
            return adbClient.currentPackageActivity(serial);
        } catch (RuntimeException ex) {
            LOG.debug("Unable to read foreground package/activity for serial={}", serial, ex);
            return "";
        }
    }

    private int normalizeMaxSteps(Integer value) {
        if (value == null) {
            return DEFAULT_MAX_STEPS;
        }
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("Max steps must be between 1 and 100");
        }
        return value;
    }

    private String androidSerial(Android android) {
        if (!StringUtils.hasText(android.getAdbHost()) || android.getAdbPort() == null) {
            throw new IllegalStateException("Android device does not have an ADB address");
        }
        return android.getAdbHost() + ":" + android.getAdbPort();
    }

    private String normalizeUpper(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(label + " is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, int max) {
        String safe = nullToEmpty(value);
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void publishTaskLog(TaskLog taskLog, String eventType) {
        publishProgress(new TaskProgressEvent()
            .setTaskLogId(taskLog.getId())
            .setTaskType(taskLog.getType())
            .setEventType(eventType)
            .setTaskLog(taskLog));
    }

    private void publishStep(TaskLog taskLog, AndroidTestStepHistory step, String eventType) {
        publishProgress(new TaskProgressEvent()
            .setTaskLogId(taskLog.getId())
            .setTaskType(taskLog.getType())
            .setEventType(eventType)
            .setTaskLog(taskLog)
            .setStep(step));
    }

    private void publishProgress(TaskProgressEvent event) {
        rabbitOperations.convertAndSend(
            RabbitMqConstant.DIRECT_EXCHANGE,
            RabbitMqConstant.Queue.TaskProgress.ROUTING_KEY,
            JsonUtils.writeJson(objectMapper, event, "Unable to serialize task progress event")
        );
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", ex);
        }
    }

    private void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            LOG.warn("Unable to delete temp file {}", path, ex);
        }
    }

    private record UiTarget(int x, int y, String label) {
    }
}
