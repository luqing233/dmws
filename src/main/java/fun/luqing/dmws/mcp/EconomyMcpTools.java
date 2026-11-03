package fun.luqing.dmws.mcp;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService;
import fun.luqing.dmws.service.dmw.economy.EconomyRobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class EconomyMcpTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DmwConfig dmwConfig;
    private final BotContainer botContainer;
    private final EconomyBankingService bankingService;
    private final EconomyRobService robService;
    private final EconomyLedgerService ledgerService;

    public ObjectNode transfer(JsonNode args) {
        long operator = localOperator();
        long groupId = requiredLong(args, "group_id");
        BigDecimal amount = EconomyMoney.parse(requiredString(args, "amount"));
        String requestId = requestId(args);
        boolean confirm = args.path("confirm").asBoolean(false);

        Resolution resolution = resolveMember(groupId, args);
        if (resolution.confirmationRequired() && !confirm) {
            return confirmationResponse("TRANSFER_TARGET_UNCERTAIN", resolution);
        }
        GroupMemberInfoResp target = resolution.target();
        if (target == null) {
            return confirmationResponse("TRANSFER_TARGET_AMBIGUOUS", resolution);
        }
        if (target.getUserId() != null && target.getUserId() == operator) {
            return error("SAME_ACCOUNT", "不能给自己转账");
        }

        EconomyBankingService.Balances before = bankingService.balances(operator);
        if (before.wallet().compareTo(amount) < 0) {
            return error("INSUFFICIENT_BALANCE", "钱包余额不足");
        }
        String targetName = displayName(target);
        EconomyBankingService.Balances after = bankingService.transfer(
                operator,
                operatorName(groupId),
                target.getUserId(),
                amount,
                operationId("transfer", requestId)
        );

        ObjectNode result = success("转账成功");
        ObjectNode data = result.putObject("data");
        data.put("target_user_id", target.getUserId());
        data.put("target_name", targetName);
        data.put("amount", EconomyMoney.format(amount));
        data.put("wallet_before", EconomyMoney.format(before.wallet()));
        data.put("wallet_after", EconomyMoney.format(after.wallet()));
        data.put("target_balance", EconomyMoney.format(
                ledgerService.balance(target.getUserId(), fun.luqing.dmws.enums.EconomyAccountScope.WALLET)
                        .balance()
        ));
        return result;
    }

    public ObjectNode rob(JsonNode args) {
        long operator = localOperator();
        long groupId = requiredLong(args, "group_id");
        String requestId = requestId(args);
        boolean confirm = args.path("confirm").asBoolean(false);

        Resolution resolution = resolveMember(groupId, args);
        if (resolution.confirmationRequired() && !confirm) {
            return confirmationResponse("ROB_TARGET_UNCERTAIN", resolution);
        }
        GroupMemberInfoResp target = resolution.target();
        if (target == null) {
            return confirmationResponse("ROB_TARGET_AMBIGUOUS", resolution);
        }
        if (target.getUserId() != null && target.getUserId() == operator) {
            return error("ROB_SELF", "不能抢劫自己");
        }

        EconomyRobService.RobResult result = robService.rob(
                operator,
                operatorName(groupId),
                target.getUserId(),
                operationId("rob", requestId)
        );
        ObjectNode response = success(result.success() ? "抢劫成功" : "抢劫结束");
        ObjectNode data = response.putObject("data");
        data.put("target_user_id", target.getUserId());
        data.put("target_name", displayName(target));
        data.put("success", result.success());
        data.put("poor_target", result.poorTarget());
        data.put("caught", result.caught());
        data.put("resisted", result.resisted());
        data.put("stolen", EconomyMoney.format(result.stolen()));
        data.put("fine", EconomyMoney.format(result.fine()));
        data.put("target_balance_after", EconomyMoney.format(result.targetBalanceAfter()));
        data.put("wallet_balance", EconomyMoney.format(result.walletBalance()));
        return response;
    }

    private Resolution resolveMember(long groupId, JsonNode args) {
        Bot bot = botContainer.robots.values().stream()
                .findFirst()
                .orElseThrow(() -> new McpToolException("OneBot 尚未连接"));
        List<GroupMemberInfoResp> members = bot.getGroupMemberList(groupId).getData();
        if (members == null || members.isEmpty()) {
            throw new McpToolException("无法获取群成员列表");
        }

        Long targetId = optionalLong(args, "target_user_id");
        String targetName = optionalString(args, "target_name");
        if (targetId == null && targetName.isBlank()) {
            throw new McpToolException("target_user_id 或 target_name 至少提供一个");
        }
        if (targetId != null) {
            GroupMemberInfoResp member = members.stream()
                    .filter(item -> targetId.equals(item.getUserId()))
                    .findFirst()
                    .orElseThrow(() -> new McpToolException("目标用户不在当前群聊"));
            return new Resolution(member, false, List.of());
        }

        String normalized = targetName.replace("@", "").trim().toLowerCase(Locale.ROOT);
        List<GroupMemberInfoResp> exact = members.stream()
                .filter(member -> equalsName(member, normalized))
                .toList();
        if (exact.size() == 1) {
            return new Resolution(exact.getFirst(), false, List.of());
        }
        if (exact.size() > 1) {
            return new Resolution(null, true, exact);
        }

        List<GroupMemberInfoResp> fuzzy = members.stream()
                .filter(member -> containsName(member, normalized))
                .limit(10)
                .toList();
        if (fuzzy.size() == 1) {
            return new Resolution(fuzzy.getFirst(), true, fuzzy);
        }
        return new Resolution(null, true, fuzzy);
    }

    private ObjectNode confirmationResponse(String code, Resolution resolution) {
        ObjectNode response = error(code, resolution.candidates().isEmpty()
                ? "未找到唯一匹配的群成员，请提供更准确的昵称或用户 ID"
                : "存在多个或模糊匹配，请确认目标用户后使用 target_user_id 和 confirm=true 重试");
        response.put("requires_confirmation", true);
        ArrayNode candidates = response.putArray("candidates");
        for (GroupMemberInfoResp member : resolution.candidates()) {
            ObjectNode candidate = candidates.addObject();
            candidate.put("user_id", member.getUserId());
            candidate.put("card", member.getCard());
            candidate.put("nickname", member.getNickname());
            candidate.put("display_name", displayName(member));
        }
        return response;
    }

    private boolean equalsName(GroupMemberInfoResp member, String normalized) {
        return normalized.equals(normalize(member.getCard()))
                || normalized.equals(normalize(member.getNickname()));
    }

    private boolean containsName(GroupMemberInfoResp member, String normalized) {
        return normalize(member.getCard()).contains(normalized)
                || normalize(member.getNickname()).contains(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String displayName(GroupMemberInfoResp member) {
        return member.getCard() != null && !member.getCard().isBlank()
                ? member.getCard().trim()
                : member.getNickname();
    }

    private String operatorName(long groupId) {
        return botContainer.robots.values().stream()
                .findFirst()
                .map(bot -> bot.getGroupMemberInfo(groupId, localOperator(), false).getData())
                .map(this::displayName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("本地操作者");
    }

    private long localOperator() {
        Long configured = dmwConfig.getMcp().getEconomy().getOperatorId();
        if (configured != null && configured > 0) {
            return configured;
        }
        long[] masters = dmwConfig.getMaster();
        if (masters != null && masters.length > 0) {
            return masters[0];
        }
        throw new McpToolException("未配置 MCP 经济操作者");
    }

    private String requestId(JsonNode args) {
        String requestId = requiredString(args, "request_id");
        if (requestId.length() > 50 || !requestId.matches("[A-Za-z0-9._:-]+")) {
            throw new McpToolException("request_id 格式无效或过长");
        }
        return requestId;
    }

    private String operationId(String action, String requestId) {
        return "mcp:" + action + ":" + requestId;
    }

    private ObjectNode success(String message) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("ok", true);
        result.put("code", "OK");
        result.put("message", message);
        return result;
    }

    private ObjectNode error(String code, String message) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("ok", false);
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    private String requiredString(JsonNode args, String name) {
        String value = optionalString(args, name);
        if (value.isBlank()) {
            throw new McpToolException(name + " 不能为空");
        }
        return value;
    }

    private String optionalString(JsonNode args, String name) {
        return args == null ? "" : args.path(name).asText("").trim();
    }

    private long requiredLong(JsonNode args, String name) {
        Long value = optionalLong(args, name);
        if (value == null) {
            throw new McpToolException(name + " 不能为空");
        }
        return value;
    }

    private Long optionalLong(JsonNode args, String name) {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull() || node.asText("").isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(node.asText());
        } catch (NumberFormatException exception) {
            throw new McpToolException(name + " 必须是整数");
        }
    }

    private record Resolution(
            GroupMemberInfoResp target,
            boolean confirmationRequired,
            List<GroupMemberInfoResp> candidates
    ) {
    }
}
