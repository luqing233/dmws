package fun.luqing.dmws.client;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import fun.luqing.dmws.config.McpConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class McpClientTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private HttpServer server;
    private final ExecutorService executor=Executors.newCachedThreadPool();
    private boolean stateful, sse, wrongId, fail, error;
    private final AtomicInteger initialized=new AtomicInteger(), calls=new AtomicInteger();
    private final Set<Long> ids=ConcurrentHashMap.newKeySet();
    private volatile boolean duplicate, badHeader;
    private McpClient client() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(executor); server.createContext("/mcp",this::handle); server.start();
        var cfg=new McpConfig.McpServer("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp");
        cfg.setTimeout(2);cfg.setReadTimeout(2);return new McpClient("test",cfg);
    }
    private void handle(HttpExchange exchange) throws java.io.IOException {
        JsonNode req=mapper.readTree(exchange.getRequestBody().readAllBytes());
        if (!req.has("id")) { exchange.sendResponseHeaders(202,-1);exchange.close();return; }
        long id=req.path("id").asLong();if(!ids.add(id))duplicate=true;
        ObjectNode out=mapper.createObjectNode().put("jsonrpc","2.0").put("id",id);
        if(req.path("method").asText().equals("initialize")) {
            initialized.incrementAndGet();out.putObject("result").put("protocolVersion","2024-11-05");
            if(stateful)exchange.getResponseHeaders().set("mcp-session-id","session-a");
        } else {
            calls.incrementAndGet();
            String header=exchange.getRequestHeaders().getFirst("mcp-session-id");
            badHeader |= stateful ? !"session-a".equals(header) : header!=null;
            if(fail) { exchange.sendResponseHeaders(503,-1);exchange.close();return; }
            if(wrongId)out.put("id",id+100);
            var payload=mapper.createObjectNode().put("code",error?"BATCH_ACTIVE":"OK").put("value","body");
            var result=out.putObject("result").put("isError",error);
            result.putArray("content").addObject().put("type","text").put("text",payload.toString());
        }
        String body=sse && calls.get()>0 ? "data: {\"jsonrpc\":\"2.0\",\"method\":\"notification\"}\n\ndata: {\"id\":-1,\"result\":{}}\n\ndata: "+out+"\n\n" : out.toString();
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",sse?"text/event-stream":"application/json");
        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    @AfterEach void close() {if(server!=null)server.stop(0);executor.shutdownNow();}
    @Test void statelessServerInitializesOnceAndOmitsSessionHeader() throws Exception {
        var client=client();assertEquals("body",client.callTool("read",null).path("value").asText());
        assertNotNull(client.callTool("read",null));assertEquals(1,initialized.get());assertFalse(badHeader);
    }
    @Test void statefulServerStillReceivesItsSessionHeader() throws Exception {
        stateful=true;assertNotNull(client().callTool("read",null));assertFalse(badHeader);
    }
    @Test void sseSelectsMatchingIdAfterNotificationsAndOtherResponses() throws Exception {
        sse=true;assertNotNull(client().callTool("read",null));
    }
    @Test void wrongResponseIdIsRejectedWithoutReplayingAWrite() throws Exception {
        wrongId=true;var client=client();assertNull(client.callTool("tnd_retry_batch",null));
        assertEquals("RPC_ID_MISMATCH",client.getLastToolErrorCode());assertEquals(1,calls.get());
    }
    @Test void uncertainWriteIsNotAutomaticallyReplayed() throws Exception {
        fail=true;var client=client();assertNull(client.callTool("tnd_cancel_batch",null));assertEquals(1,calls.get());
    }
    @Test void businessErrorPayloadIsPreservedForBatchReceipts() throws Exception {
        error=true;var client=client();var result=client.callToolResult("tnd_retry_batch",null);
        assertTrue(result.path("mcp_is_error").asBoolean());assertEquals("BATCH_ACTIVE",result.path("code").asText());
        assertEquals(1,calls.get());assertNull(client.callTool("tnd_retry_batch",null));
    }
    @Test void concurrentCallsKeepUniqueIdsAndOneHandshake() throws Exception {
        var client=client();try(var pool=Executors.newFixedThreadPool(6)) {
            List<Future<JsonNode>> futures=new ArrayList<>();
            for(int i=0;i<30;i++)futures.add(pool.submit(()->client.callTool("read",null)));
            for(var f:futures)assertNotNull(f.get(10,TimeUnit.SECONDS));
        }
        assertEquals(30,calls.get());assertEquals(1,initialized.get());assertFalse(duplicate);
    }
}
