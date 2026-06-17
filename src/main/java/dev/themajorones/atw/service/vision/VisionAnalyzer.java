package dev.themajorones.atw.service.vision;

import java.nio.file.Path;

public interface VisionAnalyzer {

    VisionResult analyze(Path screenshotPng, String objective, String uiContext);
}
