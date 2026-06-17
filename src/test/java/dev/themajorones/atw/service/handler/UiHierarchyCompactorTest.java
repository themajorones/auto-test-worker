package dev.themajorones.atw.service.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UiHierarchyCompactorTest {

    private final UiHierarchyCompactor compactor = new UiHierarchyCompactor();

    @Test
    void compactKeepsActionableAndVisibleNodes() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy>
              <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="demo" content-desc="" clickable="false" focusable="false" bounds="[0,0][1080,1920]"/>
              <node index="1" text="Login" resource-id="demo:id/login" class="android.widget.Button" package="demo" content-desc="" clickable="true" focusable="true" bounds="[10,20][110,120]"/>
              <node index="2" text="" resource-id="" class="android.widget.ImageButton" package="demo" content-desc="Settings" clickable="true" focusable="true" bounds="[200,300][260,360]"/>
            </hierarchy>
            """;

        String compact = compactor.compact(xml);

        assertThat(compact).contains("text=\"Login\"");
        assertThat(compact).contains("desc=\"Settings\"");
        assertThat(compact).doesNotContain("id=\"demo:id/login\"");
        assertThat(compact).doesNotContain("package=\"demo\"");
        assertThat(compact).contains("center=(60,70)");
        assertThat(compact).doesNotContain("FrameLayout");
    }
}
