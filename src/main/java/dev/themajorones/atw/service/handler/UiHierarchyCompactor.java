package dev.themajorones.atw.service.handler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Component
public class UiHierarchyCompactor {

    private static final int MAX_PROMPT_NODES = 40;
    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]");

    public String compact(String xml) {
        return compactForPlanner(xml).prompt();
    }

    public UiHierarchyContext compactForPlanner(String xml) {
        if (!StringUtils.hasText(xml)) {
            return new UiHierarchyContext("", Map.of(), 720, 1200);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            NodeList nodes = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagName("node");
            List<Candidate> candidates = new ArrayList<>();
            List<Candidate> contexts = new ArrayList<>();
            int width = 720;
            int height = 1200;
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                String text = attr(node, "text");
                String contentDescription = attr(node, "content-desc");
                String type = simpleClassName(attr(node, "class"));
                boolean clickable = Boolean.parseBoolean(attr(node, "clickable"));
                boolean focusable = Boolean.parseBoolean(attr(node, "focusable"));
                boolean checked = Boolean.parseBoolean(attr(node, "checked"));
                boolean selected = Boolean.parseBoolean(attr(node, "selected"));
                Bounds bounds = parseBounds(attr(node, "bounds"));
                if (bounds != null) {
                    width = Math.max(width, bounds.x2());
                    height = Math.max(height, bounds.y2());
                }
                boolean interactive = clickable || focusable;
                boolean usefulText = StringUtils.hasText(text) || StringUtils.hasText(contentDescription);
                if (!usefulText && !isTextEntry(type, interactive)) {
                    continue;
                }
                int id = i + 1;
                UiElement element = new UiElement(
                    id,
                    text,
                    contentDescription,
                    type,
                    clickable,
                    focusable,
                    checked,
                    selected,
                    bounds == null ? null : bounds.centerX(),
                    bounds == null ? null : bounds.centerY()
                );
                if (isActionTarget(element)) {
                    candidates.add(new Candidate(id, 0, element));
                } else if (isUsefulContext(type, text, contentDescription)) {
                    contexts.add(new Candidate(id, 1, element));
                }
            }
            List<Candidate> selectedTargets = selectPromptCandidates(candidates);
            List<Candidate> selectedContext = contexts.stream()
                .sorted(Comparator.comparingInt(Candidate::id))
                .limit(8)
                .toList();
            StringBuilder compact = new StringBuilder();
            Map<Integer, UiElement> elements = new LinkedHashMap<>();
            if (!selectedContext.isEmpty()) {
                compact.append("Context:\n");
                for (Candidate candidate : selectedContext) {
                    UiElement element = candidate.element();
                    compact.append("- ");
                    compact.append(element.type());
                    String label = firstText(element.text(), element.description());
                    if (StringUtils.hasText(label)) {
                        compact.append(" \"").append(truncate(label.strip(), 180)).append('"');
                    }
                    compact.append('\n');
                }
            }
            if (!selectedTargets.isEmpty()) {
                if (!compact.isEmpty()) {
                    compact.append('\n');
                }
                compact.append("Targets:\n");
            }
            for (Candidate candidate : selectedTargets) {
                UiElement element = candidate.element();
                elements.put(element.id(), element);
                compact.append('[').append(element.id()).append("] ");
                compact.append(element.type());
                String label = firstText(element.text(), element.description());
                if (StringUtils.hasText(label)) {
                    compact.append(" \"").append(truncate(label.strip(), 180)).append('"');
                } else if ("EditText".equals(element.type())) {
                    compact.append(" empty=true");
                }
                if (element.checked()) {
                    compact.append(" checked=true");
                }
                if (element.selected()) {
                    compact.append(" selected=true");
                }
                compact.append('\n');
            }
            return new UiHierarchyContext(compact.toString().strip(), elements, width, height);
        } catch (Exception ex) {
            return new UiHierarchyContext("Unable to parse UI hierarchy: " + ex.getMessage(), Map.of(), 720, 1200);
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

    private String attr(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null ? "" : value;
    }

    private boolean isUsefulContext(String type, String text, String contentDescription) {
        String label = firstText(text, contentDescription).toLowerCase();
        if (!StringUtils.hasText(label)) {
            return false;
        }
        return "TextView".equals(type)
            || label.contains("error")
            || label.contains("warning")
            || label.contains("required")
            || label.endsWith("?")
            || label.contains("allow")
            || label.contains("permission");
    }

    private boolean isActionTarget(UiElement element) {
        if (!element.clickable() && !element.focusable()) {
            return false;
        }
        return switch (element.type()) {
            case "Button", "CheckBox", "EditText", "ImageButton", "RadioButton", "Switch", "CheckedTextView" -> true;
            default -> false;
        };
    }

    private boolean isTextEntry(String type, boolean interactive) {
        return interactive && "EditText".equals(type);
    }

    private String simpleClassName(String value) {
        if (!StringUtils.hasText(value)) {
            return "Node";
        }
        int index = value.lastIndexOf('.');
        return index < 0 ? value : value.substring(index + 1);
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : StringUtils.hasText(second) ? second : "";
    }

    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "...";
    }

    private List<Candidate> selectPromptCandidates(List<Candidate> candidates) {
        return candidates.stream()
            .limit(MAX_PROMPT_NODES)
            .sorted(Comparator.comparingInt(Candidate::id))
            .toList();
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

    private record Candidate(int id, int priority, UiElement element) {
    }

    public record UiHierarchyContext(String prompt, Map<Integer, UiElement> elements, int width, int height) {
    }

    public record UiElement(
        int id,
        String text,
        String description,
        String type,
        boolean clickable,
        boolean focusable,
        boolean checked,
        boolean selected,
        Integer centerX,
        Integer centerY
    ) {
        public String label() {
            return StringUtils.hasText(text) ? text : StringUtils.hasText(description) ? description : type;
        }
    }
}
