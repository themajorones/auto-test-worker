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

        assertThat(compact).contains("[2] Button \"Login\"");
        assertThat(compact).contains("[3] ImageButton \"Settings\"");
        assertThat(compact).doesNotContain("id=\"demo:id/login\"");
        assertThat(compact).doesNotContain("package=\"demo\"");
        assertThat(compact).doesNotContain("center=(60,70)");
        assertThat(compactor.compactForPlanner(xml).elements().get(2).centerX()).isEqualTo(60);
        assertThat(compactor.compactForPlanner(xml).elements().get(2).centerY()).isEqualTo(70);
        assertThat(compact).doesNotContain("FrameLayout");
    }

    @Test
    void compactPrioritizesActionableNodesAndKeepsUsefulTextContext() {
        StringBuilder xml = new StringBuilder("""
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy>
              <node text="Install app?" resource-id="" class="android.widget.TextView" package="demo" content-desc="" clickable="false" focusable="false" bounds="[0,0][400,80]"/>
            """);
        for (int i = 0; i < 60; i++) {
            xml.append("<node text=\"Button ").append(i).append("\" resource-id=\"\" class=\"android.widget.Button\" package=\"demo\" content-desc=\"\" clickable=\"true\" focusable=\"true\" bounds=\"[0,")
                .append(100 + i)
                .append("][100,")
                .append(130 + i)
                .append("]\"/>");
        }
        xml.append("</hierarchy>");

        UiHierarchyCompactor.UiHierarchyContext context = compactor.compactForPlanner(xml.toString());

        assertThat(context.prompt()).contains("- TextView \"Install app?\"");
        assertThat(context.prompt()).doesNotContain("[1] TextView");
        assertThat(context.elements()).hasSize(40);
        assertThat(context.prompt()).doesNotContain("bounds=");
        assertThat(context.prompt()).doesNotContain("package=");
    }

    @Test
    void compactSeparatesContextFromSelectableTargets() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy>
              <node text="Lucky Patcher installer" resource-id="" class="android.widget.TextView" package="demo" content-desc="" clickable="false" focusable="false" bounds="[112,249][688,298]"/>
              <node text="" resource-id="" class="android.widget.ScrollView" package="demo" content-desc="" clickable="false" focusable="true" bounds="[12,346][708,446]"/>
              <node text="Do you really want to install the Lucky Patcher v.12.0.6?" resource-id="" class="android.widget.TextView" package="demo" content-desc="" clickable="true" focusable="true" bounds="[12,346][708,446]"/>
              <node text="Use random app name for installation" resource-id="" class="android.widget.CheckBox" package="demo" content-desc="" clickable="true" focusable="true" checked="true" bounds="[12,466][708,530]"/>
              <node text="No" resource-id="" class="android.widget.Button" package="demo" content-desc="" clickable="true" focusable="true" bounds="[22,912][345,998]"/>
              <node text="Yes" resource-id="" class="android.widget.Button" package="demo" content-desc="" clickable="true" focusable="true" bounds="[365,912][698,998]"/>
            </hierarchy>
            """;

        UiHierarchyCompactor.UiHierarchyContext context = compactor.compactForPlanner(xml);

        assertThat(context.prompt()).contains("Context:");
        assertThat(context.prompt()).contains("- TextView \"Lucky Patcher installer\"");
        assertThat(context.prompt()).contains("- TextView \"Do you really want to install the Lucky Patcher v.12.0.6?\"");
        assertThat(context.prompt()).contains("Targets:");
        assertThat(context.prompt()).contains("[4] CheckBox \"Use random app name for installation\" checked=true");
        assertThat(context.prompt()).contains("[6] Button \"Yes\"");
        assertThat(context.prompt()).doesNotContain("[1] TextView");
        assertThat(context.prompt()).doesNotContain("ScrollView");
        assertThat(context.elements()).containsOnlyKeys(4, 5, 6);
    }

    @Test
    void compactKeepsEmptyEditTextTargets() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy>
              <node text="Use your name for the application" resource-id="" class="android.widget.CheckBox" package="demo" content-desc="" clickable="true" focusable="true" checked="true" bounds="[12,818][708,882]"/>
              <node text="" resource-id="" class="android.widget.EditText" package="demo" content-desc="" clickable="true" focusable="true" bounds="[40,900][700,950]"/>
              <node text="Yes" resource-id="" class="android.widget.Button" package="demo" content-desc="" clickable="true" focusable="true" bounds="[365,980][698,1066]"/>
            </hierarchy>
            """;

        UiHierarchyCompactor.UiHierarchyContext context = compactor.compactForPlanner(xml);

        assertThat(context.prompt()).contains("[1] CheckBox \"Use your name for the application\" checked=true");
        assertThat(context.prompt()).contains("[2] EditText empty=true");
        assertThat(context.elements()).containsOnlyKeys(1, 2, 3);
        assertThat(context.elements().get(2).centerX()).isEqualTo(370);
        assertThat(context.elements().get(2).centerY()).isEqualTo(925);
    }
}
