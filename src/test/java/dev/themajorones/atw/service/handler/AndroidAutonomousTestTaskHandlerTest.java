package dev.themajorones.atw.service.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import dev.themajorones.atw.repository.AndroidRepository;
import dev.themajorones.atw.repository.AndroidTestStepHistoryRepository;
import dev.themajorones.atw.repository.ArtifactRepository;
import dev.themajorones.atw.repository.OllamaRepository;
import dev.themajorones.atw.repository.TaskLogRepository;
import dev.themajorones.atw.service.storage.ArtifactStorageClient;
import dev.themajorones.atw.service.storage.ArtifactStorageObject;
import dev.themajorones.atw.service.storage.ImageStorageClient;
import dev.themajorones.atw.service.vision.VisionAnalyzer;
import dev.themajorones.atw.service.vision.VisionResult;
import dev.themajorones.models.client.AdbClient;
import dev.themajorones.models.client.OllamaClient;
import dev.themajorones.models.constants.RabbitMqConstant;
import dev.themajorones.models.constants.TaskLogConstant;
import dev.themajorones.models.dto.AdbCommandResult;
import dev.themajorones.models.dto.RunAndroidTestRequest;
import dev.themajorones.models.dto.TaskCommandEnvelope;
import dev.themajorones.models.entity.Android;
import dev.themajorones.models.entity.AndroidTestStepHistory;
import dev.themajorones.models.entity.Artifact;
import dev.themajorones.models.entity.ArtifactSource;
import dev.themajorones.models.entity.Ollama;
import dev.themajorones.models.entity.TaskLog;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AndroidAutonomousTestTaskHandlerTest {

    @Mock
    private TaskLogRepository taskLogRepository;

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock
    private AndroidRepository androidRepository;

    @Mock
    private OllamaRepository ollamaRepository;

    @Mock
    private AndroidTestStepHistoryRepository androidTestStepHistoryRepository;

    @Mock
    private ArtifactStorageClient artifactStorageClient;

    @Mock
    private ImageStorageClient imageStorageClient;

    @Mock
    private AdbClient adbClient;

    @Mock
    private OllamaClient ollamaClient;

    @Mock
    private ApkPackageNameExtractor apkPackageNameExtractor;

    @Mock
    private VisionAnalyzer visionAnalyzer;

    @Mock
    private RabbitOperations rabbitOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AndroidAutonomousTestTaskHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AndroidAutonomousTestTaskHandler(
            taskLogRepository,
            artifactRepository,
            androidRepository,
            ollamaRepository,
            androidTestStepHistoryRepository,
            artifactStorageClient,
            imageStorageClient,
            rabbitOperations,
            adbClient,
            ollamaClient,
            apkPackageNameExtractor,
            new UiHierarchyCompactor(),
            visionAnalyzer
        );
    }

    @Test
    void handleCompletesWhenOllamaReportsSuccess() throws Exception {
        RunAndroidTestRequest request = new RunAndroidTestRequest()
            .setArtifactId(1)
            .setAndroidId(2)
            .setOllamaId(3)
            .setObjective("verify login")
            .setMaxSteps(3);
        TaskLog taskLog = new TaskLog()
            .setId(99)
            .setType(TaskLogConstant.Type.ANDROID_AUTONOMOUS_TEST)
            .setStatus(TaskLogConstant.Status.QUEUED)
            .setContent(objectMapper.writeValueAsString(request));
        Artifact artifact = new Artifact()
            .setId(1)
            .setSource(ArtifactSource.UPLOAD)
            .setName("app")
            .setStorageKey("app.apk");
        Android android = new Android()
            .setId(2)
            .setName("device")
            .setAdbHost("127.0.0.1")
            .setAdbPort(5555);
        Ollama ollama = new Ollama()
            .setId(3)
            .setName("local")
            .setBaseUrl("http://localhost:11434")
            .setModel("qwen")
            .setEnabled(true);

        when(taskLogRepository.findById(99)).thenReturn(Optional.of(taskLog));
        when(taskLogRepository.save(any(TaskLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifactRepository.findById(1)).thenReturn(Optional.of(artifact));
        when(androidRepository.findById(2)).thenReturn(Optional.of(android));
        when(ollamaRepository.findById(3)).thenReturn(Optional.of(ollama));
        when(artifactStorageClient.getObject("app.apk")).thenReturn(new ArtifactStorageObject(new ByteArrayInputStream("apk".getBytes()), 3, "application/vnd.android.package-archive"));
        when(apkPackageNameExtractor.extractPackageName(any())).thenReturn("demo.app");
        when(adbClient.connect("127.0.0.1", 5555)).thenReturn(new AdbCommandResult(0, "connected", "", Duration.ofMillis(1)));
        when(adbClient.install(eq("127.0.0.1:5555"), any())).thenReturn(new AdbCommandResult(0, "", "", Duration.ofMillis(1)));
        when(adbClient.openApp("127.0.0.1:5555", "demo.app")).thenReturn(new AdbCommandResult(0, "", "", Duration.ofMillis(1)));
        when(adbClient.dumpUiHierarchy("127.0.0.1:5555")).thenReturn("""
            <hierarchy><node text="Login" resource-id="demo:id/login" class="android.widget.Button" package="demo.app" content-desc="" clickable="true" focusable="true" bounds="[10,20][110,120]"/></hierarchy>
            """);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), "png".getBytes());
            return new AdbCommandResult(0, "", "", Duration.ofMillis(1));
        }).when(adbClient).screenshot(eq("127.0.0.1:5555"), any());
        when(adbClient.currentPackageActivity("127.0.0.1:5555")).thenReturn("demo.app/.MainActivity");
        when(visionAnalyzer.analyze(any(), anyString(), anyString())).thenReturn(new VisionResult("none", "no vision"));
        when(ollamaClient.generateStructured(eq("http://localhost:11434"), eq("qwen"), anyString(), any())).thenReturn("""
            {"reasoning":"login screen visible","action":"TERMINATE","target":null,"swipe":null,"inputText":null,"state":"SUCCESS"}
            """);

        handler.handle(new TaskCommandEnvelope()
            .setTaskLogId(99)
            .setType(TaskLogConstant.Type.ANDROID_AUTONOMOUS_TEST)
            .setContent(taskLog.getContent()));

        ArgumentCaptor<TaskLog> taskLogCaptor = ArgumentCaptor.forClass(TaskLog.class);
        verify(taskLogRepository, org.mockito.Mockito.atLeastOnce()).save(taskLogCaptor.capture());
        TaskLog saved = taskLogCaptor.getAllValues().get(taskLogCaptor.getAllValues().size() - 1);
        assertThat(saved.getStatus()).isEqualTo(TaskLogConstant.Status.SUCCESS);
        assertThat(saved.getResult()).contains("\"state\":\"SUCCESS\"");
        assertThat(saved.getResult()).contains("\"finalScreenshotStorageKey\":\"android-tests/99/steps/1.png\"");
        verify(imageStorageClient).ensureBucketExists();
        verify(imageStorageClient).putObject(eq("android-tests/99/steps/1.png"), any(), anyLong(), eq("image/png"));
        verify(rabbitOperations, atLeastOnce()).convertAndSend(
            eq(RabbitMqConstant.DIRECT_EXCHANGE),
            eq(RabbitMqConstant.Queue.TaskProgress.ROUTING_KEY),
            anyString()
        );
        ArgumentCaptor<AndroidTestStepHistory> historyCaptor = ArgumentCaptor.forClass(AndroidTestStepHistory.class);
        verify(androidTestStepHistoryRepository, atLeastOnce()).save(historyCaptor.capture());
        AndroidTestStepHistory history = historyCaptor.getValue();
        assertThat(history.getTaskLogId()).isEqualTo(99);
        assertThat(history.getStepNumber()).isEqualTo(1);
        assertThat(history.getAction()).isEqualTo("TERMINATE");
        assertThat(history.getState()).isEqualTo("SUCCESS");
        assertThat(history.getImageStorageKey()).isEqualTo("android-tests/99/steps/1.png");
    }

    @Test
    void handleInfersPermissionDialogTargetWhenClickCoordinatesAreMissing() throws Exception {
        RunAndroidTestRequest request = new RunAndroidTestRequest()
            .setArtifactId(1)
            .setAndroidId(2)
            .setOllamaId(3)
            .setObjective("open the app and allow what it asks for")
            .setMaxSteps(2);
        TaskLog taskLog = new TaskLog()
            .setId(100)
            .setType(TaskLogConstant.Type.ANDROID_AUTONOMOUS_TEST)
            .setStatus(TaskLogConstant.Status.QUEUED)
            .setContent(objectMapper.writeValueAsString(request));
        Artifact artifact = new Artifact()
            .setId(1)
            .setSource(ArtifactSource.UPLOAD)
            .setName("app")
            .setStorageKey("app.apk");
        Android android = new Android()
            .setId(2)
            .setName("device")
            .setAdbHost("127.0.0.1")
            .setAdbPort(5555);
        Ollama ollama = new Ollama()
            .setId(3)
            .setName("local")
            .setBaseUrl("http://localhost:11434")
            .setModel("qwen")
            .setEnabled(true);

        when(taskLogRepository.findById(100)).thenReturn(Optional.of(taskLog));
        when(taskLogRepository.save(any(TaskLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifactRepository.findById(1)).thenReturn(Optional.of(artifact));
        when(androidRepository.findById(2)).thenReturn(Optional.of(android));
        when(ollamaRepository.findById(3)).thenReturn(Optional.of(ollama));
        when(artifactStorageClient.getObject("app.apk")).thenReturn(new ArtifactStorageObject(new ByteArrayInputStream("apk".getBytes()), 3, "application/vnd.android.package-archive"));
        when(apkPackageNameExtractor.extractPackageName(any())).thenReturn("demo.app");
        when(adbClient.connect("127.0.0.1", 5555)).thenReturn(new AdbCommandResult(0, "connected", "", Duration.ofMillis(1)));
        when(adbClient.install(eq("127.0.0.1:5555"), any())).thenReturn(new AdbCommandResult(0, "", "", Duration.ofMillis(1)));
        when(adbClient.openApp("127.0.0.1:5555", "demo.app")).thenReturn(new AdbCommandResult(0, "", "", Duration.ofMillis(1)));
        when(adbClient.dumpUiHierarchy("127.0.0.1:5555")).thenReturn("""
            <hierarchy>
              <node text="Allow LP Installer to access photos, media, and files on your device?" resource-id="com.android.permissioncontroller:id/permission_message" class="android.widget.TextView" package="com.android.permissioncontroller" content-desc="" clickable="false" focusable="false" bounds="[430,430][670,510]"/>
              <node text="ALLOW" resource-id="com.android.permissioncontroller:id/permission_allow_button" class="android.widget.Button" package="com.android.permissioncontroller" content-desc="" clickable="true" focusable="true" bounds="[492,520][604,576]"/>
              <node text="DON'T ALLOW" resource-id="com.android.permissioncontroller:id/permission_deny_button" class="android.widget.Button" package="com.android.permissioncontroller" content-desc="" clickable="true" focusable="true" bounds="[468,590][628,646]"/>
            </hierarchy>
            """);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), "png".getBytes());
            return new AdbCommandResult(0, "", "", Duration.ofMillis(1));
        }).when(adbClient).screenshot(eq("127.0.0.1:5555"), any());
        when(adbClient.currentPackageActivity("127.0.0.1:5555")).thenReturn("demo.app/.MainActivity");
        when(visionAnalyzer.analyze(any(), anyString(), anyString())).thenReturn(new VisionResult("none", "permission dialog visible"));
        when(ollamaClient.generateStructured(eq("http://localhost:11434"), eq("qwen"), anyString(), any()))
            .thenReturn("""
                {"reasoning":"permission prompt is visible, tap allow","action":"CLICK","target":null,"swipe":null,"inputText":null,"state":"IN_PROGRESS"}
                """)
            .thenReturn("""
                {"reasoning":"permission was handled","action":"TERMINATE","target":null,"swipe":null,"inputText":null,"state":"SUCCESS"}
                """);

        handler.handle(new TaskCommandEnvelope()
            .setTaskLogId(100)
            .setType(TaskLogConstant.Type.ANDROID_AUTONOMOUS_TEST)
            .setContent(taskLog.getContent()));

        verify(adbClient).tap("127.0.0.1:5555", 548, 548);
        ArgumentCaptor<AndroidTestStepHistory> historyCaptor = ArgumentCaptor.forClass(AndroidTestStepHistory.class);
        verify(androidTestStepHistoryRepository, atLeastOnce()).save(historyCaptor.capture());
        assertThat(historyCaptor.getAllValues()).anySatisfy(history -> {
            assertThat(history.getStepNumber()).isEqualTo(1);
            assertThat(history.getAction()).isEqualTo("CLICK");
            assertThat(history.getTargetX()).isEqualTo(548);
            assertThat(history.getTargetY()).isEqualTo(548);
            assertThat(history.getError()).isNull();
        });
    }

    @Test
    void handleStoresValidationErrorsInStepActionResult() throws Exception {
        RunAndroidTestRequest request = new RunAndroidTestRequest()
            .setArtifactId(1)
            .setAndroidId(2)
            .setOllamaId(3)
            .setObjective("type something")
            .setMaxSteps(1);
        TaskLog taskLog = new TaskLog()
            .setId(101)
            .setType(TaskLogConstant.Type.ANDROID_AUTONOMOUS_TEST)
            .setStatus(TaskLogConstant.Status.QUEUED)
            .setContent(objectMapper.writeValueAsString(request));
        Artifact artifact = new Artifact()
            .setId(1)
            .setSource(ArtifactSource.UPLOAD)
            .setName("app")
            .setStorageKey("app.apk");
        Android android = new Android()
            .setId(2)
            .setName("device")
            .setAdbHost("127.0.0.1")
            .setAdbPort(5555);
        Ollama ollama = new Ollama()
            .setId(3)
            .setName("local")
            .setBaseUrl("http://localhost:11434")
            .setModel("qwen")
            .setEnabled(true);

        when(taskLogRepository.findById(101)).thenReturn(Optional.of(taskLog));
        when(taskLogRepository.save(any(TaskLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifactRepository.findById(1)).thenReturn(Optional.of(artifact));
        when(androidRepository.findById(2)).thenReturn(Optional.of(android));
        when(ollamaRepository.findById(3)).thenReturn(Optional.of(ollama));
        when(artifactStorageClient.getObject("app.apk")).thenReturn(new ArtifactStorageObject(new ByteArrayInputStream("apk".getBytes()), 3, "application/vnd.android.package-archive"));
        when(apkPackageNameExtractor.extractPackageName(any())).thenReturn("demo.app");
        when(adbClient.connect("127.0.0.1", 5555)).thenReturn(new AdbCommandResult(0, "connected", "", Duration.ofMillis(1)));
        when(adbClient.install(eq("127.0.0.1:5555"), any())).thenReturn(new AdbCommandResult(0, "", "", Duration.ofMillis(1)));
        when(adbClient.openApp("127.0.0.1:5555", "demo.app")).thenReturn(new AdbCommandResult(0, "", "", Duration.ofMillis(1)));
        when(adbClient.dumpUiHierarchy("127.0.0.1:5555")).thenReturn("""
            <hierarchy><node text="Username" resource-id="demo:id/user" class="android.widget.EditText" package="demo.app" content-desc="" clickable="true" focusable="true" bounds="[10,20][110,120]"/></hierarchy>
            """);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), "png".getBytes());
            return new AdbCommandResult(0, "", "", Duration.ofMillis(1));
        }).when(adbClient).screenshot(eq("127.0.0.1:5555"), any());
        when(adbClient.currentPackageActivity("127.0.0.1:5555")).thenReturn("demo.app/.MainActivity");
        when(visionAnalyzer.analyze(any(), anyString(), anyString())).thenReturn(new VisionResult("none", "field visible"));
        when(ollamaClient.generateStructured(eq("http://localhost:11434"), eq("qwen"), anyString(), any()))
            .thenReturn("""
                {"reasoning":"tap the text field","action":"ENTER_TEXT","target":null,"swipe":null,"inputText":null,"state":"IN_PROGRESS"}
                """);

        assertThatThrownBy(() -> handler.handle(new TaskCommandEnvelope()
            .setTaskLogId(101)
            .setType(TaskLogConstant.Type.ANDROID_AUTONOMOUS_TEST)
            .setContent(taskLog.getContent())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Android autonomous test failed");

        ArgumentCaptor<AndroidTestStepHistory> historyCaptor = ArgumentCaptor.forClass(AndroidTestStepHistory.class);
        verify(androidTestStepHistoryRepository, atLeastOnce()).save(historyCaptor.capture());
        assertThat(historyCaptor.getAllValues()).anySatisfy(history -> {
            assertThat(history.getStepNumber()).isEqualTo(1);
            assertThat(history.getAction()).isEqualTo("ENTER_TEXT");
            assertThat(history.getActionResult()).contains("ERROR: ENTER_TEXT requires inputText");
            assertThat(history.getError()).contains("ENTER_TEXT requires inputText");
        });
    }
}
