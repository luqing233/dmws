package fun.luqing.dmws.mcp;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.ActionList;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.config.McpConfig;
import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserItem;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyItemService;
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

    @Test
    void queriesShopBalanceAndBackpackWithoutConnectedBot() {
        DmwConfig config = new DmwConfig();
        config.setMaster(new long[]{10001L});
        config.setBotId(10001L);
        EconomyMcpTools tools = new EconomyMcpTools(config, new BotContainer(), bankingService,
                itemService, robService, ledgerService, org.mockito.Mockito.mock(fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService.class));
        EconomyItemDefinition item = new EconomyItemDefinition();
        item.setCode("red-bull");
        item.setName("红牛");
        item.setPrice(new BigDecimal("100.25"));
        when(itemService.shop()).thenReturn(List.of(item));
        assertEquals("100.25", tools.shop().path("items").get(0).path("unit_price").asText());
        when(itemService.fishingShop()).thenReturn(List.of(item));
        assertEquals("fishing",tools.shop(MAPPER.createObjectNode().put("category","fishing")).path("category").asText());
        verify(itemService).fishingShop();
        when(bankingService.balances(10001L)).thenReturn(new EconomyBankingService.Balances(
                new BigDecimal("1.25"), new BigDecimal("2.50")));
        assertEquals("3.75", tools.balance(MAPPER.createObjectNode()).path("total").asText());
        when(bankingService.balances(10001L)).thenReturn(new EconomyBankingService.Balances(
                new BigDecimal("10.00"), new BigDecimal("-20.00"), new BigDecimal("130.00")));
        var debtBalance = tools.balance(MAPPER.createObjectNode());
        assertEquals("130.00", debtBalance.path("total_debt").asText());
        assertEquals("-120.00", debtBalance.path("total").asText());
        assertTrue(debtBalance.path("display_text").asText().contains("总欠款：130.00"));
        EconomyUserItem stack = new EconomyUserItem();
        stack.setItemCode("red-bull");
        stack.setQuantity(2);
        when(itemService.backpack(20001L)).thenReturn(List.of(stack));
        when(itemService.definition("red-bull")).thenReturn(item);
        var args = MAPPER.createObjectNode().put("user_id", 20001);
        when(itemService.equipment(20001L)).thenReturn(new fun.luqing.dmws.service.dmw.economy.EconomyEquipmentService.EquipmentView(
                List.of(new fun.luqing.dmws.service.dmw.economy.EconomyEquipmentService.BatonView(60, true, 40)), true));
        assertEquals(2, tools.backpack(args).path("items").get(0).path("quantity").asInt());
        assertEquals(60, tools.backpack(args).path("equipment").path("batons").get(0).path("energy").asInt());
        assertTrue(tools.backpack(args).path("equipment").path("charger_owned").asBoolean());
        assertEquals(40, tools.backpack(args).path("equipment").path("batons").get(0).path("minutes_to_full").asInt());
        args.put("user_id", -1);
        org.junit.jupiter.api.Assertions.assertThrows(McpToolException.class, () -> tools.balance(args));
        verifyNoInteractions(bot);
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private Bot bot;

    @Mock
    private EconomyBankingService bankingService;

    @Mock
    private EconomyItemService itemService;

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
                new EconomyMcpTools(
                        config,
                        container,
                        bankingService,
                        itemService,
                        robService,
                        ledgerService, org.mockito.Mockito.mock(fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService.class)
                );
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
        when(bankingService.balances(10001L))
                .thenReturn(new EconomyBankingService.Balances(
                        new BigDecimal("1000.00"),
                        BigDecimal.ZERO
                ));
        when(bankingService.transfer(
                10001L,
                "这位玩家",
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
                new EconomyMcpTools(
                        config,
                        container,
                        bankingService,
                        itemService,
                        robService,
                        ledgerService, org.mockito.Mockito.mock(fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService.class)
                );
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
                "这位玩家",
                20001L,
                new BigDecimal("100.00"),
                "mcp:transfer:req-2"
        );
    }

    @Test
    void buyByChineseNameUsesRequestingUserWalletAndBankFallback() throws Exception {
        DmwConfig config = configWithOperator(10001L);
        BotContainer container = new BotContainer();
        container.robots.put(10001L, bot);

        GroupMemberInfoResp buyer = member(20001L, "伊蕾娜", "伊蕾娜");
        ActionList<GroupMemberInfoResp> list = new ActionList<>();
        list.setData(List.of(buyer));
        when(bot.getGroupMemberList(30001L)).thenReturn(list);

        EconomyItemDefinition redBull = new EconomyItemDefinition();
        redBull.setCode("red-bull");
        redBull.setName("红牛");
        redBull.setPrice(new BigDecimal("888.00"));
        when(itemService.resolve("红牛")).thenReturn(redBull);
        when(ledgerService.balance(20001L, EconomyAccountScope.WALLET))
                .thenReturn(new EconomyLedgerService.AccountBalance(
                        20001L,
                        EconomyAccountScope.WALLET,
                        new BigDecimal("100.00")
                ));
        when(ledgerService.balance(20001L, EconomyAccountScope.MAIN_BANK))
                .thenReturn(new EconomyLedgerService.AccountBalance(
                        20001L,
                        EconomyAccountScope.MAIN_BANK,
                        new BigDecimal("1000.00")
                ));
        when(itemService.buy(
                20001L,
                "伊蕾娜",
                "red-bull",
                1,
                "mcp:buy:buy-red-bull-1"
        )).thenReturn(new EconomyItemService.PurchaseResult(
                "red-bull",
                "红牛",
                1,
                new BigDecimal("888.00"),
                new BigDecimal("788.00"),
                new BigDecimal("0.00"),
                new BigDecimal("212.00")
        ));
        EconomyUserItem owned = new EconomyUserItem();
        owned.setUserId(20001L);
        owned.setItemCode("red-bull");
        owned.setQuantity(1);
        when(itemService.backpack(20001L)).thenReturn(List.of(owned));

        EconomyMcpTools tools =
                new EconomyMcpTools(
                        config,
                        container,
                        bankingService,
                        itemService,
                        robService,
                        ledgerService, org.mockito.Mockito.mock(fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService.class)
                );
        ObjectNode args = MAPPER.createObjectNode();
        args.put("group_id", 30001L);
        args.put("user_id", 20001L);
        args.put("item", "红牛");
        args.put("request_id", "buy-red-bull-1");

        ObjectNode result = tools.buy(args);

        assertTrue(result.path("ok").asBoolean());
        assertEquals("red-bull", result.path("data").path("item_code").asText());
        assertEquals("0.00", result.path("data").path("wallet_after").asText());
        assertEquals("212.00", result.path("data").path("bank_after").asText());
        assertEquals("788.00", result.path("data").path("bank_top_up").asText());
        assertEquals(1, result.path("data").path("owned_quantity").asInt());
        verify(itemService).buy(
                20001L,
                "伊蕾娜",
                "red-bull",
                1,
                "mcp:buy:buy-red-bull-1"
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
