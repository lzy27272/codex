package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeComGroupRobotClientTest {
    private static final URI WEBHOOK = URI.create(
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=group-secret-value");

    @Test
    void sendsAReportDeepLinkAsMarkdown() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeComGroupRobotClient client = new WeComGroupRobotClient(builder);
        server.expect(requestTo(WEBHOOK.toString()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("打开处理中台填报日报")))
                .andExpect(content().string(containsString("daily-reports")))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        client.sendReportLink(WEBHOOK, "日报待填报", "请及时完成", URI.create(
                "https://www.sfgzt.cn/api/v1/integrations/wecom/oauth/start?returnTo=%23%2Fdaily-reports%2F00000000-0000-0000-0000-000000000001"));
        server.verify();
    }

    @Test
    void transportExceptionsNeverExposeTheWebhookCredential() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeComGroupRobotClient client = new WeComGroupRobotClient(builder);
        server.expect(requestTo(WEBHOOK.toString())).andRespond(withException(new IOException(WEBHOOK.toString())));

        assertThatThrownBy(() -> client.sendReportLink(
                WEBHOOK, "日报待填报", "请及时完成", URI.create("https://www.sfgzt.cn/")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("group-secret-value")
                .hasNoCause();
    }
}
