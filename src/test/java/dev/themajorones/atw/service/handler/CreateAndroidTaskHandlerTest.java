package dev.themajorones.atw.service.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.themajorones.atw.repository.AndroidRepository;
import dev.themajorones.atw.repository.DockerRepository;
import dev.themajorones.atw.repository.TaskLogRepository;
import dev.themajorones.models.client.DockerClient;
import dev.themajorones.models.constants.AndroidType;
import dev.themajorones.models.constants.TaskLogConstant;
import dev.themajorones.models.dto.CreateAndroidRequest;
import dev.themajorones.models.dto.TaskCommandEnvelope;
import dev.themajorones.models.entity.Android;
import dev.themajorones.models.entity.Docker;
import dev.themajorones.models.entity.TaskLog;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CreateAndroidTaskHandlerTest {

    @Mock
    private TaskLogRepository taskLogRepository;

    @Mock
    private AndroidRepository androidRepository;

    @Mock
    private DockerRepository dockerRepository;

    @Mock
    private DockerClient dockerClient;

    private CreateAndroidTaskHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new CreateAndroidTaskHandler(taskLogRepository, androidRepository, dockerRepository, dockerClient);
    }

    @Test
    void handleCreatesAndroidRowOnlyAfterContainerIsReady() throws Exception {
        CreateAndroidRequest request = new CreateAndroidRequest()
            .setType(AndroidType.REDROID.name())
            .setDockerId(7)
            .setName("pixel")
            .setImage("redroid/redroid:latest")
            .setAccelerationMode("host")
            .setWidth(1080)
            .setHeight(1920)
            .setDpi(420);

        TaskLog taskLog = new TaskLog()
            .setId(99)
            .setType(TaskLogConstant.Type.CREATE_ANDROID)
            .setStatus(TaskLogConstant.Status.QUEUED)
            .setContent(objectMapper.writeValueAsString(request));

        Docker docker = new Docker()
            .setId(7)
            .setName("docker")
            .setBaseUrl("http://docker.example");

        when(taskLogRepository.findById(99)).thenReturn(Optional.of(taskLog));
        when(taskLogRepository.save(any(TaskLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dockerRepository.findById(7)).thenReturn(Optional.of(docker));
        when(dockerClient.imageExists("http://docker.example", "redroid/redroid:latest")).thenReturn(false, true);
        when(dockerClient.createAndroidContainer(eq("http://docker.example"), eq("99"), any(CreateAndroidRequest.class))).thenReturn("container-1");
        when(dockerClient.isContainerRunning("http://docker.example", "container-1")).thenReturn(true);
        when(dockerClient.mappedAdbPort("http://docker.example", "container-1")).thenReturn(5555);
        when(dockerClient.isTcpPortReachable("docker.example", 5555, Duration.ofSeconds(2))).thenReturn(true);
        when(dockerClient.hostFromBaseUrl("http://docker.example")).thenReturn("docker.example");
        when(androidRepository.save(any(Android.class))).thenAnswer(invocation -> {
            Android android = invocation.getArgument(0);
            android.setId(33);
            return android;
        });

        handler.handle(new TaskCommandEnvelope().setTaskLogId(99).setType(TaskLogConstant.Type.CREATE_ANDROID).setContent(taskLog.getContent()));

        ArgumentCaptor<Android> androidCaptor = ArgumentCaptor.forClass(Android.class);
        verify(androidRepository).save(androidCaptor.capture());
        Android savedAndroid = androidCaptor.getValue();
        assertThat(savedAndroid.getType()).isEqualTo(AndroidType.REDROID.name());
        assertThat(savedAndroid.getContainerId()).isEqualTo("container-1");
        assertThat(savedAndroid.getContainerName()).isEqualTo("tmos-android-99");
        assertThat(savedAndroid.getAdbHost()).isEqualTo("docker.example");
        assertThat(savedAndroid.getAdbPort()).isEqualTo(5555);
        assertThat(savedAndroid.getDetails().getAccelerationMode()).isEqualTo("HOST");
        assertThat(savedAndroid.getDetails().getWidth()).isEqualTo(1080);
        assertThat(savedAndroid.getDetails().getHeight()).isEqualTo(1920);
        assertThat(savedAndroid.getDetails().getDpi()).isEqualTo(420);

        ArgumentCaptor<TaskLog> taskLogCaptor = ArgumentCaptor.forClass(TaskLog.class);
        verify(taskLogRepository, atLeastOnce()).save(taskLogCaptor.capture());
        assertThat(taskLogCaptor.getAllValues().get(taskLogCaptor.getAllValues().size() - 1).getStatus())
            .isEqualTo(TaskLogConstant.Status.SUCCESS);
    }

    @Test
    void handleDoesNotPersistAndroidRowWhenContainerCreationFails() throws Exception {
        CreateAndroidRequest request = new CreateAndroidRequest()
            .setType(AndroidType.REDROID.name())
            .setDockerId(7)
            .setName("pixel")
            .setImage("redroid/redroid:latest")
            .setAccelerationMode("host");

        TaskLog taskLog = new TaskLog()
            .setId(99)
            .setType(TaskLogConstant.Type.CREATE_ANDROID)
            .setStatus(TaskLogConstant.Status.QUEUED)
            .setContent(objectMapper.writeValueAsString(request));

        Docker docker = new Docker()
            .setId(7)
            .setName("docker")
            .setBaseUrl("http://docker.example");

        when(taskLogRepository.findById(99)).thenReturn(Optional.of(taskLog));
        when(taskLogRepository.save(any(TaskLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dockerRepository.findById(7)).thenReturn(Optional.of(docker));
        when(dockerClient.imageExists("http://docker.example", "redroid/redroid:latest")).thenReturn(true);
        when(dockerClient.createAndroidContainer(eq("http://docker.example"), eq("99"), any(CreateAndroidRequest.class))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> handler.handle(new TaskCommandEnvelope().setTaskLogId(99).setType(TaskLogConstant.Type.CREATE_ANDROID).setContent(taskLog.getContent())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Android creation failed");

        verify(androidRepository, never()).save(any(Android.class));

        ArgumentCaptor<TaskLog> taskLogCaptor = ArgumentCaptor.forClass(TaskLog.class);
        verify(taskLogRepository, atLeastOnce()).save(taskLogCaptor.capture());
        assertThat(taskLogCaptor.getAllValues().get(taskLogCaptor.getAllValues().size() - 1).getStatus())
            .isEqualTo(TaskLogConstant.Status.FAILED);
    }
}
