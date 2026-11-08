package fun.luqing.dmws.mcp;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.service.dmw.economy.EconomyConditionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyConditionMcpToolsTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService names =
            new fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService(new fun.luqing.dmws.config.DmwConfig(),
                    new com.mikuac.shiro.core.BotContainer(), mock(fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository.class));

    @Test
    void bailQuoteDoesNotPayAndReturnsSnakeCaseFeesAndDates() {
        var identity = mock(EconomyMcpTools.class);
        var service = mock(EconomyConditionService.class);
        var bot = mock(Bot.class);
        var member = new GroupMemberInfoResp();
        member.setNickname("担保人"); member.setUserId(100L);
        var args = mapper.createObjectNode().put("group_id", 300L).put("target_user_id", 200L).put("request_id", "bail-quote");
        when(identity.optionalLong(args, "user_id")).thenReturn(null);
        when(identity.botUserId()).thenReturn(100L);
        when(identity.requiredLong(args, "group_id")).thenReturn(300L);
        when(identity.requiredLong(args, "target_user_id")).thenReturn(200L);
        when(identity.economyBot()).thenReturn(bot);
        when(identity.requireGroupMember(bot, 300L, 100L)).thenReturn(member);
        var prisoner = new GroupMemberInfoResp();
        prisoner.setUserId(200L); prisoner.setCard("被保释人的群名片");
        when(identity.requireGroupMember(bot, 300L, 200L)).thenReturn(prisoner);
        when(identity.requestId(args)).thenReturn("bail-quote");
        when(identity.messageOperation("bail", args, 100L, "", "bail", "bail-quote")).thenReturn("mcp:bail:bail-quote");
        when(service.status(200L, "")).thenReturn(new EconomyConditionService.Status(200L, 50, 0, null,
                LocalDateTime.of(2026, 11, 4, 12, 20), BigDecimal.ZERO, new BigDecimal("1100"), null, null, null));
        var tools = new EconomyConditionMcpTools(identity, service, names);
        var quote = tools.execute("dmw_economy_bail", args);
        assertTrue(quote.path("requires_confirmation").asBoolean());
        assertEquals("1100.00", quote.path("data").path("bail_fee").asText());
        assertEquals("2026-11-04T12:20", quote.path("data").path("jail_until").asText());
        verify(service, never()).bail(anyLong(), any(), anyLong(), any());
        when(service.bail(100L, "担保人", 200L, "mcp:bail:bail-quote")).thenReturn(
                new EconomyConditionService.BailResult(100L, 200L, new BigDecimal("1100"), 50, BigDecimal.ZERO, new BigDecimal("100")));
        args.put("confirm", true);
        var paid = tools.execute("dmw_economy_bail", args);
        assertTrue(paid.path("ok").asBoolean());
        assertEquals("1100.00", paid.path("data").path("fee").asText());
        assertEquals("被保释人的群名片", paid.path("data").path("target_name").asText());
        assertTrue(paid.path("display_text").asText().contains("被保释人的群名片 已出狱"));
        assertFalse(paid.path("display_text").asText().contains("200"));
        verify(service).bail(100L, "担保人", 200L, "mcp:bail:bail-quote");
    }

    @Test
    void dischargeUsesRequestingMemberAndPreservesBusinessError() {
        var identity = mock(EconomyMcpTools.class);
        var service = mock(EconomyConditionService.class);
        var bot = mock(Bot.class);
        var member = new GroupMemberInfoResp();
        member.setCard("用户"); member.setUserId(200L);
        var args = mapper.createObjectNode().put("group_id", 300L).put("user_id", 200L).put("request_id", "out-1");
        when(identity.optionalLong(args, "user_id")).thenReturn(200L);
        when(identity.requiredLong(args, "group_id")).thenReturn(300L);
        when(identity.economyBot()).thenReturn(bot);
        when(identity.requireGroupMember(bot, 300L, 200L)).thenReturn(member);
        when(identity.requestId(args)).thenReturn("out-1");
        when(identity.messageOperation("discharge", args, 200L, "", "discharge", "out-1")).thenReturn("mcp:discharge:out-1");
        when(service.dischargeReceipt(200L, "用户", "mcp:discharge:out-1")).thenThrow(new EconomyException("NOT_HOSPITALIZED", "当前未住院"));
        var result = new EconomyConditionMcpTools(identity, service, names).execute("dmw_economy_discharge", args);
        assertFalse(result.path("ok").asBoolean());
        assertEquals("NOT_HOSPITALIZED", result.path("code").asText());
        var status = new EconomyConditionService.Status(200L,0,0,null,null,BigDecimal.ZERO,BigDecimal.ZERO,
                new BigDecimal("655"),BigDecimal.ZERO,LocalDateTime.of(2026,11,4,21,54));
        doReturn(new EconomyConditionService.DischargeReceipt(status,true,BigDecimal.ZERO))
                .when(service).dischargeReceipt(200L,"用户","mcp:discharge:out-1");
        var success = new EconomyConditionMcpTools(identity,service,names).execute("dmw_economy_discharge",args);
        assertEquals("出院成功\n已缴纳医药费：655.00 金币",success.path("display_text").asText());
        assertFalse(success.path("display_text").asText().contains("欠款"));
        verify(identity, never()).botUserId();
    }

    @Test
    void locationUsesQueriedPlayerAndReturnsNoWalletData() {
        var identity = mock(EconomyMcpTools.class);
        var service = mock(EconomyConditionService.class);
        var args = mapper.createObjectNode().put("user_id", 200L);
        when(identity.optionalLong(args, "user_id")).thenReturn(200L);
        var location = new EconomyConditionService.Location(200L, "HOSPITAL", "医院", LocalDateTime.of(2026,11,5,20,58), null);
        when(service.location(200L, "")).thenReturn(location);
        when(service.describeLocation(location, "这位玩家")).thenReturn("当前位置：医院\n最晚强制出院：明天 20:58");
        var result = new EconomyConditionMcpTools(identity, service, names).execute("dmw_economy_get_location", args);
        assertTrue(result.path("ok").asBoolean());
        assertEquals(200L, result.path("data").path("user_id").asLong());
        assertEquals("HOSPITAL", result.path("data").path("location").asText());
        assertFalse(result.toString().contains("wallet"));
        assertFalse(result.toString().contains("balance"));
        assertTrue(result.path("display_text").asText().contains("明天 20:58"));
        verify(identity, never()).economyBot();
    }

    @Test
    void routesConditionToolsAndMarksErrors() {
        var condition = mock(EconomyConditionMcpTools.class);
        var tools = new DmwMcpTools(mock(TomatoMcpTools.class), mock(EconomyMcpTools.class), mock(ForwardMcpTools.class), condition, mock(EconomyMarketMcpTools.class));
        var args = mapper.createObjectNode();
        when(condition.execute("dmw_economy_bail", args)).thenReturn(mapper.createObjectNode().put("ok", false).put("code", "BAIL_BAD_REPUTATION"));
        assertTrue(tools.execute("dmw_economy_bail", args).path("isError").asBoolean());
        verify(condition).execute("dmw_economy_bail", args);
    }
}
