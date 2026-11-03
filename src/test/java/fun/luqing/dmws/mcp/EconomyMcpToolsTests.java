package fun.luqing.dmws.mcp;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.ActionList;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.config.McpConfig;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService;
import fun.luqing.dmws.service.dmw.economy.EconomyRobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EconomyMcpToolsTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private Bot bot;

    @Mock
    private EconomyBankingService bankingService;

    @Mock
    private EconomyRobService robService;

    @Mock
    private EconomyLedgerService ledgerService;

    @Test
    void ambiguousTargetRequiresUserConfirmationAndDoesNotTransfer() throws Exception {
        DmwConfig config = new DmwConfig();
        config.setMaster(new long[]{10001L});
        McpConfig mcpConfig = new McpConfig();
        mcpConfig.getEconomy().setOperatorId(10001L);
        config.setMcp(mcpConfig);

        BotContainer container = new BotContainer();
        container.robots.put(10001L, bot);
        GroupMemberInfoResp first = member(20001L, "伊蕾娜", "同名");
        GroupMemberInfoResp second = member(20002L, "伊蕾娜", "同名");
        ActionList<GroupMemberInfoResp> response = new ActionList<>();
        response.setData(List.of(first, second));
        when(bot.getGroupMemberList(30001L)).thenReturn(response);

        EconomyMcpTools tools =
                new EconomyMcpTools(config, container, bankingService, robService, ledgerService);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("group_id", 30001L);
        args.put("target_name", "伊蕾娜");
        args.put("amount", "100");
        args.put("request_id", "request-1");

        ObjectNode result = tools.transfer(args);

        assertFalse(result.path("ok").asBoolean());
        assertTrue(result.path("requires_confirmation").asBoolean());
        assertEquals(2, result.path("candidates").size());
        verifyNoInteractions(bankingService);
    }

    @Test
    void uniqueExactTargetExecutesDirectly() throws Exception {
        DmwConfig config = configWithOperator(10001L);
        BotContainer container = new BotContainer();
        container.robots.put(10001L, bot);

        GroupMemberInfoResp target = member(20001L, "伊蕾娜", "伊蕾娜");
        GroupMemberInfoResp operator = member(10001L, "主人", "主人");
        ActionList<GroupMemberInfoResp> list = new ActionList<>();
        list.setData(List.of(target, operator));
        when(bot.getGroupMemberList(30001L)).thenReturn(list);

        ActionData<GroupMemberInfoResp> operatorResponse = new ActionData<>();
        operatorResponse.setData(operator);
        when(bot.getGroupMemberInfo(30001L, 10001L, false)).thenReturn(operatorResponse);
        when(bankingService.balances(10001L))
                .thenReturn(new EconomyBankingService.Balances(
                        new BigDecimal("1000.00"),
                        BigDecimal.ZERO
                ));
        when(bankingService.transfer(
                10001L,
                "主人",
                20001L,
                new BigDecimal("100.00"),
                "mcp:transfer:req-2"
        )).thenReturn(new EconomyBankingService.Balances(
                new BigDecimal("900.00"),
                BigDecimal.ZERO
        ));
        when(ledgerService.balance(20001L, EconomyAccountScope.WALLET))
                .thenReturn(new EconomyLedgerService.AccountBalance(
                        20001L,
                        EconomyAccountScope.WALLET,
                        new BigDecimal("500.00")
                ));

        EconomyMcpTools tools =
                new EconomyMcpTools(config, container, bankingService, robService, ledgerService);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("group_id", 30001L);
        args.put("target_name", "伊蕾娜");
        args.put("amount", "100");
        args.put("request_id", "req-2");

        ObjectNode result = tools.transfer(args);

        assertTrue(result.path("ok").asBoolean());
        assertEquals("伊蕾娜", result.path("data").path("target_name").asText());
        verify(bankingService).transfer(
                10001L,
                "主人",
                20001L,
                new BigDecimal("100.00"),
                "mcp:transfer:req-2"
        );
    }

    private DmwConfig configWithOperator(long operator) {
        DmwConfig config = new DmwConfig();
        config.setMaster(new long[]{operator});
        McpConfig mcpConfig = new McpConfig();
        mcpConfig.getEconomy().setOperatorId(operator);
        config.setMcp(mcpConfig);
        return config;
    }

    private GroupMemberInfoResp member(long userId, String nickname, String card) {
        GroupMemberInfoResp member = new GroupMemberInfoResp();
        member.setUserId(userId);
        member.setNickname(nickname);
        member.setCard(card);
        return member;
    }
}
