package fun.luqing.dmws.mcp;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.MsgId;
import fun.luqing.dmws.config.DmwConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardMcpToolsTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsGroupAndPrivateAndReturnsReceipt() {
        Bot bot = mock(Bot.class);
        DmwConfig config = new DmwConfig();
        config.setBotId(123L);
        config.setBotName("DMW");
        BotContainer container = new BotContainer();
        container.robots.put(123L, bot);
        when(bot.getSelfId()).thenReturn(123L);
        ActionData<MsgId> response = new ActionData<>();
        response.setRetCode(0);
        MsgId id = new MsgId();
        id.setMessageId(42);
        response.setData(id);
        when(bot.sendGroupForwardMsg(eq(456L), anyList())).thenReturn(response);
        when(bot.sendPrivateForwardMsg(eq(456L), anyList())).thenReturn(response);
        ForwardMcpTools tools = new ForwardMcpTools(container, config);
        for (String target : new String[]{"group_id", "user_id"}) {
            var args = mapper.createObjectNode().put(target, 456);
            args.putArray("nodes").add("第一条").add("[CQ:at,qq=all]");
            assertTrue(tools.send(args).path("ok").asBoolean());
        }
        verify(bot).sendGroupForwardMsg(eq(456L), argThat(nodes ->
                nodes.size() == 2 && nodes.get(1).toString().contains("type=text")));
    }

    @Test
    void rejectsInvalidTargetsNodesAndOfflineBot() {
        ForwardMcpTools tools = new ForwardMcpTools(new BotContainer(), new DmwConfig());
        var args = mapper.createObjectNode();
        args.putArray("nodes").add("hello");
        assertThrows(McpToolException.class, () -> tools.send(args));
        args.put("group_id", 1).put("user_id", 2);
        assertThrows(McpToolException.class, () -> tools.send(args));
        args.remove("user_id");
        assertThrows(McpToolException.class, () -> tools.send(args));
        args.putArray("nodes");
        assertThrows(McpToolException.class, () -> tools.send(args));
    }

    @Test
    void doesNotReportFailedSendAsSuccess() {
        Bot bot = mock(Bot.class);
        DmwConfig config = new DmwConfig();
        config.setBotId(123L);
        config.setBotName("DMW");
        BotContainer container = new BotContainer();
        container.robots.put(123L, bot);
        var args = mapper.createObjectNode().put("group_id", 456);
        args.putArray("nodes").add("hello");
        ForwardMcpTools tools = new ForwardMcpTools(container, config);
        assertThrows(McpToolException.class, () -> tools.send(args));
        verify(bot, times(1)).sendGroupForwardMsg(eq(456L), anyList());
    }
}