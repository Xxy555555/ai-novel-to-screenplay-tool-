package com.scriptforge.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 通用 OpenAI 兼容客户端鲁棒性：当上游把 chat/completions 响应头误标为
 * {@code application/octet-stream}（聚合网关偶发），仍应能正确解析出 content。
 * 用 MockRestServiceServer 模拟该响应，无需联网。
 */
class OpenAiCompatibleClientTest {

    private LlmProperties props() {
        LlmProperties p = new LlmProperties();
        p.setBaseUrl("https://example.test/v1");
        p.setModel("m");
        p.setApiKey("k");
        return p;
    }

    @Test
    void parsesContentEvenWhenContentTypeIsOctetStream() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(props(), builder);

        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"已将标题改为《群山回唱》。\"}}]}",
                        MediaType.APPLICATION_OCTET_STREAM));

        String out = client.complete("sys", "user");
        assertEquals("已将标题改为《群山回唱》。", out);
        server.verify();
    }

    @Test
    void retriesOnceOnTransientUpstreamError() {
        // 大/慢响应时聚合网关偶发瞬时错误（5xx / 提取失败）——应自动重试一次后成功。
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(props(), builder);

        server.expect(ExpectedCount.times(1), requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(ExpectedCount.times(1), requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"已重写所有场景。\"}}]}",
                        MediaType.APPLICATION_JSON));

        String out = client.complete("sys", "user");
        assertEquals("已重写所有场景。", out);
        server.verify();
    }

    @Test
    void chatStreamAggregatesSseDeltas() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(props(), builder);

        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"已把\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"S1 改\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"得更紧张。\"}}]}\n\n"
                + "data: [DONE]\n\n";
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andRespond(withSuccess(sse, MediaType.parseMediaType("text/event-stream")));

        StringBuilder got = new StringBuilder();
        String full = client.chatStream("sys",
                java.util.List.of(new LlmClient.ChatMessage("user", "hi")), got::append);

        assertEquals("已把S1 改得更紧张。", full);
        assertEquals("已把S1 改得更紧张。", got.toString());
        server.verify();
    }
}
