package cn.sifangguan.hotelaios.integrations.wecom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** XXE-safe parser for the small subset of WeCom directory callback fields we consume. */
@Component
@ConditionalOnProperty(
        name = {"app.wecom.enabled", "app.wecom.directory-sync-enabled"}, havingValue = "true"
)
public class WeComDirectoryXml {
    private static final int MAX_XML_LENGTH = 64 * 1024;

    public String encryptedEnvelope(String xml) {
        return required(parse(xml).get("encrypt"), "Encrypt");
    }

    public WeComDirectoryEvent parseEvent(String plaintext) {
        Map<String, String> values = parse(plaintext);
        String event = lower(required(values.get("event"), "Event"));
        String changeType = lower(required(values.get("changetype"), "ChangeType"));
        String userId = bounded(required(values.get("userid"), "UserID"), 128, "UserID");
        String newUserId = boundedOptional(values.get("newuserid"), 128);
        String name = boundedOptional(values.get("name"), 120);
        String status = boundedOptional(values.get("status"), 16);
        String departments = boundedOptional(values.get("department"), 1000);
        String position = boundedOptional(values.get("position"), 240);
        long epochSecond;
        try {
            epochSecond = Long.parseLong(required(values.get("createtime"), "CreateTime"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Directory callback CreateTime is invalid");
        }
        return new WeComDirectoryEvent(event, changeType, userId, newUserId,
                name == null ? "企业微信新员工" : name, status, departments, position,
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    private Map<String, String> parse(String xml) {
        if (xml == null || xml.isBlank() || xml.length() > MAX_XML_LENGTH) {
            throw new IllegalArgumentException("Directory callback XML is missing or too large");
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            factory.setProperty(XMLInputFactory.IS_COALESCING, true);
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            Map<String, String> values = new LinkedHashMap<>();
            String current = null;
            StringBuilder text = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    current = lower(reader.getLocalName());
                    text.setLength(0);
                } else if ((event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) && current != null) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && current != null) {
                    String closing = lower(reader.getLocalName());
                    if (closing.equals(current)) values.putIfAbsent(current, text.toString().trim());
                    current = null;
                    text.setLength(0);
                }
            }
            reader.close();
            return values;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Directory callback XML is invalid");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Directory callback misses " + field);
        }
        return value.trim();
    }

    private static String bounded(String value, int max, String field) {
        if (value.length() > max || value.chars().anyMatch(character -> character < 32)) {
            throw new IllegalArgumentException("Directory callback " + field + " is invalid");
        }
        return value;
    }

    private static String boundedOptional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return bounded(value.trim(), max, "field");
    }

    private static String lower(String value) { return value.toLowerCase(Locale.ROOT); }
}
