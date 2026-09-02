package cn.sifangguan.hotelaios.integrations.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Sends a bounded Markdown link through one previously validated group robot destination. */
@Service
@ConditionalOnProperty(name = {"app.wecom.enabled", "app.wecom.group-robot.delivery-enabled"}, havingValue = "true")
public class WeComGroupRobotClient {
    private final RestClient restClient;

    @Autowired
    public WeComGroupRobotClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.wecom.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.wecom.http.read-timeout-ms:5000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(500, Math.min(connectTimeoutMs, 30000)));
        requestFactory.setReadTimeout(Math.max(1000, Math.min(readTimeoutMs, 60000)));
        restClientBuilder.requestFactory(requestFactory);
        this.restClient = restClientBuilder.build();
    }

    WeComGroupRobotClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public void sendReportLink(URI webhook, String title, String description, URI deepLink) {
        String content = "## " + bounded(title, 120) + "\n"
                + bounded(description, 1200) + "\n\n"
                + "[打开处理中台填报日报](" + deepLink + ")";
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("content", content);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);

        JsonNode response;
        try {
            response = restClient.post().uri(webhook).contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().body(JsonNode.class);
        } catch (RuntimeException exception) {
            // RestClient exceptions can retain the full Webhook URI. Never keep
            // the cause because that URI contains the robot credential.
            throw new IllegalStateException("WeCom group robot request failed ("
                    + exception.getClass().getSimpleName() + ")");
        }
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("WeCom group robot returned an invalid response");
        }
        int errorCode = response.path("errcode").asInt(-1);
        if (errorCode != 0) {
            throw new IllegalStateException("WeCom group robot failed with errcode " + errorCode);
        }
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() <= maximum) return normalized;
        return normalized.substring(0, maximum - 1) + "…";
    }
}
