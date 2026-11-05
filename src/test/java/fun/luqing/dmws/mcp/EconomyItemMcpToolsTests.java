package fun.luqing.dmws.mcp;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.common.ActionList;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserItem;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyItemService;
import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService;
import fun.luqing.dmws.service.dmw.economy.EconomyRobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EconomyItemMcpToolsTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DmwConfig config = new DmwConfig();
    private final BotContainer container = new BotContainer();
    @Mock private Bot bot;
    @Mock private EconomyItemService items;
    private EconomyMcpTools tools;

    @Test
    void robberyAndTransferDefaultToBotRatherThanConfiguredAdministrator() {
        connectWithMember(10002L, "机器人");
        var args = arguments().put("target_user_id", 10002L).put("amount", "1");
        assertEquals("ROB_SELF", tools.rob(args).path("code").asText());
        assertEquals("SAME_ACCOUNT", tools.transfer(args).path("code").asText());
    }

    @Test
    void trustedSourceOverridesModelRequestIdForCrossTransportDeduplication() {
        connectWithMember(20001L, "用户甲");
        when(items.resolve("红牛")).thenReturn(definition());
        String id = fun.luqing.dmws.common.economy.EconomyMessageOperation.id("item-use", 30001L, -19L, 20001L, "red-bull");
        when(items.use(20001L, "用户甲", "red-bull", 1, id)).thenReturn("使用成功");
        var args = arguments().put("user_id", 20001L).put("_source_message_id", -19L).put("request_id", "model-retry-1");
        tools.useItem(args);
        tools.useItem(args.put("request_id", "model-retry-2"));
        verify(items, times(2)).use(20001L, "用户甲", "red-bull", 1, id);
    }

    @BeforeEach
    void setUp() {
        config.setBotId(10002L);
        config.setMaster(new long[]{10001L});
        config.getMcp().getEconomy().setOperatorId(10001L);
        tools = new EconomyMcpTools(config, container, mock(EconomyBankingService.class),
                items, mock(EconomyRobService.class), mock(EconomyLedgerService.class), mock(fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService.class));
    }

    @Test
    void usesRequestingUsersItemsByChineseNameWithDefaultQuantity() {
        connectWithMember(20001L, "用户甲");
        when(items.resolve("红牛")).thenReturn(definition());
        when(items.use(20001L, "用户甲", "red-bull", 1, "mcp:use:message-1"))
                .thenReturn("使用成功，下一次抢劫成功率提高 20%");
        var result = tools.useItem(arguments().put("user_id", 20001L));
        assertTrue(result.path("ok").asBoolean());
        assertEquals(20001L, result.path("data").path("user_id").asLong());
        assertEquals(1, result.path("data").path("quantity").asInt());
        assertEquals("ROB_BOOST", result.path("data").path("effect_type").asText());
        assertEquals("message-1", result.path("data").path("request_id").asText());
        assertTrue(result.path("data").path("effect_message").asText().contains("20%"));
        verify(items).use(20001L, "用户甲", "red-bull", 1, "mcp:use:message-1");
    }

    @Test
    void omittedUserUsesConfiguredBotAccountAndMatchingConnection() {
        Bot other = mock(Bot.class);
        container.robots.put(10001L, other);
        connectWithMember(10002L, "机器人");
        when(items.resolve("红牛")).thenReturn(definition());
        when(items.use(10002L, "机器人", "red-bull", 2, "mcp:use:message-1")).thenReturn("使用成功");
        var result = tools.useItem(arguments().put("quantity", 2));
        assertEquals(10002L, result.path("data").path("user_id").asLong());
        verify(items).use(10002L, "机器人", "red-bull", 2, "mcp:use:message-1");
        verifyNoInteractions(other);
    }

    @Test
    void unconfiguredIdentityUsesSoleConnectedBotNeverMaster() {
        config.setBotId(0);
        connectWithMember(10002L, "机器人");
        when(items.resolve("红牛")).thenReturn(definition());
        when(items.use(10002L, "机器人", "red-bull", 1, "mcp:use:message-1")).thenReturn("使用成功");
        assertEquals(10002L, tools.useItem(arguments()).path("data").path("user_id").asLong());
    }

    @Test
    void cannotFallBackToMasterOrArbitraryBotWhenIdentityIsUnknown() {
        config.setBotId(0);
        assertThrows(McpToolException.class, () -> tools.useItem(arguments()));
        container.robots.put(10002L, bot);
        container.robots.put(10003L, mock(Bot.class));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments()));
        verifyNoInteractions(items, bot);
    }

    @Test
    void configuredOfflineBotDoesNotBorrowAnotherConnection() {
        container.robots.put(10003L, bot);
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("user_id", 20001L)));
        verifyNoInteractions(items, bot);
    }

    @Test
    void rejectsNonMembersBeforeTouchingInventory() {
        connectWithMember(10002L, "机器人");
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("user_id", 20001L)));
        verifyNoInteractions(items);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1000})
    void rejectsInvalidQuantitiesWithoutCallingOneBotOrItems(int quantity) {
        var result = tools.useItem(arguments().put("quantity", quantity));
        assertFalse(result.path("ok").asBoolean());
        assertEquals("INVALID_QUANTITY", result.path("code").asText());
        verifyNoInteractions(items, bot);
    }

    @Test
    void rejectsInvalidIdentityAndRequestArgumentsBeforeExecution() {
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("group_id", 0)));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("user_id", -1)));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().putNull("user_id")));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("user_id", " ")));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("quantity", 1.5)));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().putNull("quantity")));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("request_id", "bad id")));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("request_id", "a".repeat(51))));
        assertThrows(McpToolException.class, () -> tools.useItem(arguments().put("item", " ")));
        verifyNoInteractions(items, bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ITEM_NOT_ENOUGH", "PASSIVE_ITEM", "NO_BROKEN_STREAK"})
    void preservesBusinessErrorCodesWithoutBuyingOrRetrying(String code) {
        connectWithMember(10002L, "机器人");
        when(items.resolve("红牛")).thenReturn(definition());
        when(items.use(10002L, "机器人", "red-bull", 1, "mcp:use:message-1"))
                .thenThrow(new EconomyException(code, "业务失败"));
        var result = tools.useItem(arguments());
        assertFalse(result.path("ok").asBoolean());
        assertEquals(code, result.path("code").asText());
        verify(items).resolve("红牛");
        verify(items).use(10002L, "机器人", "red-bull", 1, "mcp:use:message-1");
        verifyNoMoreInteractions(items);
    }

    @Test
    void duplicateUseReturnsExplicitNoConsumptionError() {
        connectWithMember(10002L, "机器人");
        when(items.resolve("红牛")).thenReturn(definition());
        when(items.use(10002L, "机器人", "red-bull", 1, "mcp:use:message-1"))
                .thenThrow(new DuplicateEconomyOperationException("mcp:use:message-1"));
        var result = tools.useItem(arguments());
        assertEquals("DUPLICATE_REQUEST", result.path("code").asText());
        assertFalse(result.path("ok").asBoolean());
        verify(items).use(10002L, "机器人", "red-bull", 1, "mcp:use:message-1");
    }

    @Test
    void botBackpackCanBeReadOfflineAndMarksPassiveItems() {
        var stack = new EconomyUserItem();
        stack.setItemCode("double-sign-card");
        stack.setQuantity(1);
        var definition = definition();
        definition.setEffectType(EconomyItemEffectType.SIGN_BONUS);
        when(items.backpack(10002L)).thenReturn(List.of(stack));
        when(items.definition("double-sign-card")).thenReturn(definition);
        var result = tools.backpack(MAPPER.createObjectNode().put("as_bot", true));
        assertEquals(10002L, result.path("user_id").asLong());
        assertFalse(result.path("items").get(0).path("manual_usable").asBoolean());
        assertEquals("SIGN_BONUS", result.path("items").get(0).path("effect_type").asText());
        assertThrows(McpToolException.class, () -> tools.backpack(
                MAPPER.createObjectNode().put("as_bot", true).put("user_id", 20001L)));
        assertThrows(McpToolException.class, () -> tools.backpack(MAPPER.createObjectNode().put("as_bot", "true")));
        verifyNoInteractions(bot);
    }

    private ObjectNode arguments() {
        return MAPPER.createObjectNode().put("group_id", 30001L).put("item", "红牛").put("request_id", "message-1");
    }

    @Test
    void reusableChargerReceiptReportsZeroItemsConsumedForTheActualRequester() {
        connectWithMember(20001L, "用户甲");
        var charger = new EconomyItemDefinition(); charger.setCode("electric-baton-charger");
        charger.setName("电棍充电器"); charger.setEffectType(EconomyItemEffectType.BATON_CHARGER);
        when(items.resolve("电棍充电器")).thenReturn(charger);
        when(items.use(20001L, "用户甲", "electric-baton-charger", 1, "mcp:use:message-1")).thenReturn("电棍已接上充电器");
        var result=tools.useItem(arguments().put("user_id",20001L).put("item","电棍充电器"));
        assertTrue(result.path("ok").asBoolean());assertEquals(0,result.path("data").path("consumed_quantity").asInt(-1));
        assertEquals(20001L,result.path("data").path("user_id").asLong());assertEquals("电棍已接上充电器",result.path("display_text").asText());
    }

    private EconomyItemDefinition definition() {
        var definition = new EconomyItemDefinition();
        definition.setCode("red-bull");
        definition.setName("红牛");
        definition.setEffectType(EconomyItemEffectType.ROB_BOOST);
        return definition;
    }

    private void connectWithMember(long userId, String name) {
        container.robots.put(10002L, bot);
        var member = new GroupMemberInfoResp();
        member.setUserId(userId);
        member.setCard(name);
        var response = new ActionList<GroupMemberInfoResp>();
        response.setData(List.of(member));
        when(bot.getGroupMemberList(30001L)).thenReturn(response);
    }
}
