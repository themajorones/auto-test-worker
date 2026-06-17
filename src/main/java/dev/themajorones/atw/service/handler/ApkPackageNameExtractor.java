package dev.themajorones.atw.service.handler;

import java.nio.file.Path;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import net.dongliu.apk.parser.ApkFile;

@Component
public class ApkPackageNameExtractor {

    public String extractPackageName(Path apk) {
        try (ApkFile apkFile = new ApkFile(apk.toFile())) {
            String packageName = apkFile.getApkMeta().getPackageName();
            if (!StringUtils.hasText(packageName)) {
                throw new IllegalStateException("APK package name is empty");
            }
            return packageName.trim();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to infer package name from APK", ex);
        }
    }
}
