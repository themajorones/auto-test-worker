package dev.themajorones.atw.service.handler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Component
public class UiHierarchyCompactor {

    private static final int MAX_NODES = 90;
    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]");

    public String compact(String xml) {
        if (!StringUtils.hasText(xml)) {
            return "";
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            NodeList nodes = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagName("node");
            StringBuilder compact = new StringBuilder();
            int kept = 0;
            for (int i = 0; i < nodes.getLength() && kept < MAX_NODES; i++) {
                Element node = (Element) nodes.item(i);
                String text = attr(node, "text");
                String contentDescription = attr(node, "content-desc");
                boolean clickable = Boolean.parseBoolean(attr(node, "clickable"));
                boolean focusable = Boolean.parseBoolean(attr(node, "focusable"));
                if (!clickable && !focusable && !StringUtils.hasText(text) && !StringUtils.hasText(contentDescription)) {
                    continue;
                }
                Bounds bounds = parseBounds(attr(node, "bounds"));
                compact.append(kept + 1).append(". ");
                appendField(compact, "text", text);
                appendField(compact, "desc", contentDescription);
                appendField(compact, "class", attr(node, "class"));
                compact.append("clickable=").append(clickable).append(' ');
                compact.append("focusable=").append(focusable).append(' ');
                compact.append("bounds=").append(attr(node, "bounds"));
                if (bounds != null) {
                    compact.append(" center=(").append(bounds.centerX()).append(',').append(bounds.centerY()).append(')');
                }
                compact.append('\n');
                kept++;
            }
            return compact.toString().strip();
        } catch (Exception ex) {
            return "Unable to parse UI hierarchy: " + ex.getMessage();
        }
    }

    public String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash UI hierarchy", ex);
        }
    }

    private void appendField(StringBuilder builder, String name, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(name).append("=\"").append(value.strip()).append("\" ");
        }
    }

    private String attr(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null ? "" : value;
    }

    private Bounds parseBounds(String value) {
        Matcher matcher = BOUNDS.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            return null;
        }
        return new Bounds(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3)),
            Integer.parseInt(matcher.group(4))
        );
    }

    private record Bounds(int x1, int y1, int x2, int y2) {
        int centerX() {
            return (x1 + x2) / 2;
        }

        int centerY() {
            return (y1 + y2) / 2;
        }
    }
}
