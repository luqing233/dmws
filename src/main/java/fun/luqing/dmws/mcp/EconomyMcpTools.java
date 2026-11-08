package fun.luqing.dmws.mcp;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyItemService;
import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService;
import fun.luqing.dmws.service.dmw.economy.EconomyRobService;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
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
    private final EconomyItemService itemService;
    private final EconomyRobService robService;
    private final EconomyLedgerService ledgerService;
    private final EconomyPlayerNameService names;

    public ObjectNode shop() { return shop(MAPPER.createObjectNode()); }
    public ObjectNode shop(JsonNode args) {
        String category=args.path("category").asText("general");
        if(!java.util.Set.of("general","fishing").contains(category)) throw new EconomyException("INVALID_CATEGORY","商店分类为general或fishing");
        ObjectNode result = success("商店查询成功");
        ArrayNode items = result.putArray("items");
        for (EconomyItemDefinition item : category.equals("fishing")?itemService.fishingShop():itemService.shop()) {
            items.addObject().put("item_code", item.getCode()).put("item_name", item.getName())
                    .put("description", item.getDescription()).put("unit_price", item.getPrice().toPlainString())
                    .put("effect_type", item.getEffectType().name())
                    .put("manual_usable", item.getEffectType() != EconomyItemEffectType.SIGN_BONUS);
        }
        result.put("category",category);
        result.put("count", items.size());
        return result;
    }

    public ObjectNode balance(JsonNode args) {
        long userId = queryUserId(args);
        var balances = bankingService.balances(userId);
        ObjectNode result = success("余额查询成功");
        result.put("user_id", userId).put("wallet", balances.wallet().toPlainString())
                .put("user_name", names.resolve(optionalLong(args, "group_id"), userId))
                .put("main_bank", balances.bank().toPlainString())
                .put("total", balances.wallet().add(balances.bank()).toPlainString());
        return result;
    }

    public ObjectNode backpack(JsonNode args) {
        long userId = queryUserId(args);
        ObjectNode result = success("背包查询成功");
        result.put("user_id", userId).put("user_name", names.resolve(optionalLong(args, "group_id"), userId));
        ArrayNode items = result.putArray("items");
        for (var stack : itemService.backpack(userId)) {
            var row = items.addObject().put("item_code", stack.getItemCode()).put("quantity", stack.getQuantity());
            try {
                var definition = itemService.definition(stack.getItemCode());
                row.put("item_name", definition.getName()).put("description", definition.getDescription());
                row.put("effect_type", definition.getEffectType().name())
                        .put("manual_usable", Boolean.TRUE.equals(definition.getEnabled())
                                && definition.getEffectType() != EconomyItemEffectType.SIGN_BONUS);
            } catch (EconomyException e) {
                row.put("item_name", stack.getItemCode()).put("manual_usable", false);
            }
        }
        result.put("count", items.size());
        var equipmentView = itemService.equipment(userId);
        if (equipmentView != null) {
            var state = result.putObject("equipment").put("charger_owned", equipmentView.chargerOwned());
            var batons = state.putArray("batons");
            for (var baton : equipmentView.batons()) batons.addObject().put("energy", baton.energy())
                    .put("charging", baton.charging()).put("minutes_to_full", baton.minutesToFull());
        }
        return result;
    }

    private long queryUserId(JsonNode args) {
        Long specified = optionalLong(args, "user_id");
        JsonNode asBot = args == null ? null : args.get("as_bot");
        if (asBot != null && !asBot.isBoolean()) throw new McpToolException("as_bot 必须是布尔值");
        if (asBot != null && asBot.asBoolean()) {
            if (specified != null) throw new McpToolException("as_bot=true 时不能同时提供 user_id");
            return botUserId();
        }
        long userId = specified == null ? botUserId() : specified;
        if (userId <= 0) throw new McpToolException("user_id 必须为正整数");
        return userId;
    }

    public ObjectNode useItem(JsonNode args) {
        long groupId = requiredLong(args, "group_id");
        Long specified = optionalLong(args, "user_id");
        if (args.has("user_id") && specified == null) {
            throw new McpToolException("user_id 必须为正整数；使用机器人自己的道具时应省略此参数");
        }
        if (groupId <= 0 || (specified != null && specified <= 0)) {
            throw new McpToolException("group_id 和 user_id 必须为正整数");
        }
        String itemQuery = requiredString(args, "item");
        String requestId = requestId(args);
        if (args.has("quantity") && optionalLong(args, "quantity") == null) {
            throw new McpToolException("quantity 必须为整数；省略时默认使用 1 个");
        }
        int quantity = optionalInt(args, "quantity", 1);
        if (quantity < 1 || quantity > 999) {
            return error("INVALID_QUANTITY", "使用数量必须在 1 到 999 之间");
        }
        long userId = specified == null ? botUserId() : specified;
        GroupMemberInfoResp owner = requireGroupMember(economyBot(), groupId, userId);
        EconomyItemDefinition item;
        String effectMessage;
        try {
            item = itemService.resolve(itemQuery);
            effectMessage = itemService.use(userId, displayName(owner), item.getCode(), quantity,
                    messageOperation("item-use", args, userId, item.getCode(), "use", requestId));
        } catch (DuplicateEconomyOperationException exception) {
            return error("DUPLICATE_REQUEST", "该 request_id 已处理过，本次未重复消耗道具；如需再次使用请使用新的请求号");
        } catch (EconomyException exception) {
            return error(exception.getCode(), exception.getMessage());
        }
        ObjectNode result = success(effectMessage);
        result.put("display_text", effectMessage);
        result.putObject("data").put("user_id", userId).put("user_name", displayName(owner))
                .put("item_code", item.getCode()).put("item_name", item.getName())
                .put("quantity", quantity).put("effect_type", item.getEffectType().name())
                .put("consumed_quantity", item.getEffectType() == EconomyItemEffectType.ELECTRIC_BATON
                        || item.getEffectType() == EconomyItemEffectType.BATON_CHARGER ? 0 : quantity)
                .put("effect_message", effectMessage).put("request_id", requestId);
        return result;
    }

    /** AI's own account is the bot account, never the master/local operator fallback. */
    long botUserId() {
        if (dmwConfig.getBotId() > 0) return dmwConfig.getBotId();
        if (botContainer.robots.size() == 1) {
            long userId = botContainer.robots.keySet().iterator().next();
            if (userId > 0) return userId;
        }
        throw new McpToolException("无法确定机器人账号，请配置 dmws.bot-id 或仅连接一个 OneBot 账号");
    }

    Bot economyBot() {
        if (dmwConfig.getBotId() > 0) {
            Bot bot = botContainer.robots.get(dmwConfig.getBotId());
            if (bot == null) throw new McpToolException("配置的 OneBot 机器人尚未连接");
            return bot;
        }
        if (botContainer.robots.size() == 1) return botContainer.robots.values().iterator().next();
        throw new McpToolException("OneBot 尚未连接或存在多个账号，请配置 dmws.bot-id");
    }

    public ObjectNode transfer(JsonNode args) {
        long operator = actingUser(args);
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
                actingName(groupId, operator),
                target.getUserId(),
                amount,
                messageOperation("transfer", args, operator, "", "transfer", requestId)
        );

        ObjectNode result = success("转账成功");
        result.put("display_text", "转账成功：向 " + targetName + " 转账 " + EconomyMoney.format(amount) + " 金币");
        ObjectNode data = result.putObject("data");
        data.put("user_id", operator);
        data.put("user_name", names.resolve(groupId, operator));
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
        long operator = actingUser(args);
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
                actingName(groupId, operator),
                target.getUserId(),
                messageOperation("rob", args, operator, "", "rob", requestId)
        );
        ObjectNode response = success(result.success() ? "抢劫成功" : "抢劫结束");
        response.put("display_text", robService.describe(result, displayName(target)));
        ObjectNode data = response.putObject("data");
        data.put("user_id", operator);
        data.put("user_name", names.resolve(groupId, operator));
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
        data.put("rob_boost_used", result.robBoostUsed());
        data.put("electric_baton_used", result.electricBatonUsed());
        if (result.electricBatonEnergy() == null) data.putNull("electric_baton_energy");
        else data.put("electric_baton_energy", result.electricBatonEnergy());
        data.put("victim_assaulted", result.victimAssaulted());
        data.put("injured_user_id", result.injuredUserId());
        data.put("injury", result.injury());
        data.put("hospital_until", result.hospitalUntil() == null ? null : result.hospitalUntil().toString());
        data.put("jail_until", result.jailUntil() == null ? null : result.jailUntil().toString());
        data.put("crime", result.crime());
        return response;
    }

    public ObjectNode buy(JsonNode args) {
        long userId = requiredLong(args, "user_id");
        long groupId = requiredLong(args, "group_id");
        String itemQuery = requiredString(args, "item");
        String requestId = requestId(args);
        int quantity = optionalInt(args, "quantity", 1);
        if (quantity < 1 || quantity > 999) {
            return error("INVALID_QUANTITY", "购买数量必须在 1 到 999 之间");
        }

        GroupMemberInfoResp buyer = requireGroupMember(groupId, userId);
        EconomyItemDefinition item;
        BigDecimal total;
        try {
            item = itemService.resolve(itemQuery);
            total = EconomyMoney.positive(
                    item.getPrice().multiply(BigDecimal.valueOf(quantity))
            );
        } catch (EconomyException exception) {
            return error(exception.getCode(), exception.getMessage());
        }

        BigDecimal walletBefore = ledgerService
                .balance(userId, EconomyAccountScope.WALLET)
                .balance();
        BigDecimal bankBefore = ledgerService
                .balance(userId, EconomyAccountScope.MAIN_BANK)
                .balance();
        BigDecimal available = walletBefore.add(bankBefore.max(BigDecimal.ZERO));
        if (available.compareTo(total) < 0) {
            return error(
                    "INSUFFICIENT_BALANCE",
                    "钱包和主银行余额不足：需要 " + EconomyMoney.format(total)
                            + "，钱包 " + EconomyMoney.format(walletBefore)
                            + "，主银行 " + EconomyMoney.format(bankBefore)
            );
        }

        EconomyItemService.PurchaseResult purchase = null;
        try {
            purchase = itemService.buy(
                    userId,
                    displayName(buyer),
                    item.getCode(),
                    quantity,
                    messageOperation("item-buy", args, userId, item.getCode(), "buy", requestId)
            );
        } catch (DuplicateEconomyOperationException exception) {
            return error(
                    "DUPLICATE_REQUEST",
                    "该 request_id 已处理过，本次未重复扣款；如需再次购买请使用新的请求号"
            );
        } catch (EconomyException exception) {
            return error(exception.getCode(), exception.getMessage());
        }

        BigDecimal walletAfter = purchase == null
                ? ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
                : purchase.walletBalance();
        BigDecimal bankAfter = purchase == null
                ? ledgerService.balance(userId, EconomyAccountScope.MAIN_BANK).balance()
                : purchase.bankBalance();
        BigDecimal bankTopUp = purchase == null
                ? EconomyMoney.zero()
                : purchase.bankTopUp();
        int ownedQuantity = itemService.backpack(userId).stream()
                .filter(stack -> item.getCode().equals(stack.getItemCode()))
                .mapToInt(stack -> stack.getQuantity())
                .findFirst()
                .orElse(0);

        ObjectNode result = success("购买成功");
        result.put("display_text", "购买成功：" + item.getName() + " x" + quantity + "，花费 " + EconomyMoney.format(total) + " 金币");
        ObjectNode data = result.putObject("data");
        data.put("user_id", userId);
        data.put("user_name", displayName(buyer));
        data.put("item_code", item.getCode());
        data.put("item_name", item.getName());
        data.put("quantity", quantity);
        data.put("unit_price", EconomyMoney.format(item.getPrice()));
        data.put("total_cost", EconomyMoney.format(total));
        data.put("wallet_before", EconomyMoney.format(walletBefore));
        data.put("wallet_after", EconomyMoney.format(walletAfter));
        data.put("bank_before", EconomyMoney.format(bankBefore));
        data.put("bank_after", EconomyMoney.format(bankAfter));
        data.put("bank_top_up", EconomyMoney.format(bankTopUp));
        data.put("owned_quantity", ownedQuantity);
        return result;
    }

    private Resolution resolveMember(long groupId, JsonNode args) {
        Bot bot = connectedBot();
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

    private GroupMemberInfoResp requireGroupMember(long groupId, long userId) {
        return requireGroupMember(connectedBot(), groupId, userId);
    }

    GroupMemberInfoResp requireGroupMember(Bot bot, long groupId, long userId) {
        var response = bot.getGroupMemberList(groupId);
        List<GroupMemberInfoResp> members = response == null ? null : response.getData();
        if (members == null || members.isEmpty()) {
            throw new McpToolException("无法获取群成员列表");
        }
        return members.stream()
                .filter(member -> member.getUserId() != null && member.getUserId() == userId)
                .findFirst()
                .orElseThrow(() -> new McpToolException("用户不在当前群聊"));
    }

    private Bot connectedBot() {
        return economyBot();
    }

    private ObjectNode confirmationResponse(String code, Resolution resolution) {
        ObjectNode response = error(code, resolution.candidates().isEmpty()
                ? "未找到唯一匹配的群友，请提供更准确的昵称或直接 @对方"
                : "存在重名或近似昵称，请先确认具体群友，再发起操作");
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
        String name = EconomyPlayerNameService.visible(member.getCard(), member.getNickname(), member.getUserId());
        return name.isBlank() ? names.resolve(null, member.getUserId()) : name;
    }

    private String actingName(long groupId, long userId) {
        String resolved = names.resolve(groupId, userId);
        return resolved == null || resolved.isBlank() ? "这位玩家" : resolved;
    }

    long actingUser(JsonNode args) {
        Long specified = optionalLong(args, "user_id");
        if (args != null && args.has("user_id") && (specified == null || specified <= 0)) {
            throw new McpToolException("user_id 必须为正整数");
        }
        return specified == null ? botUserId() : specified;
    }

    String messageOperation(String action, JsonNode args, long owner, String detail, String legacyAction, String requestId) {
        if (args.has("_source_message_id")) {
            return fun.luqing.dmws.common.economy.EconomyMessageOperation.id(action,
                    requiredLong(args, "group_id"), requiredLong(args, "_source_message_id"), owner, detail);
        }
        return operationId(legacyAction, requestId);
    }

    String requestId(JsonNode args) {
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

    long requiredLong(JsonNode args, String name) {
        Long value = optionalLong(args, name);
        if (value == null) {
            throw new McpToolException(name + " 不能为空");
        }
        return value;
    }

    Long optionalLong(JsonNode args, String name) {
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

    private int optionalInt(JsonNode args, String name, int defaultValue) {
        Long value = optionalLong(args, name);
        if (value == null) {
            return defaultValue;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new McpToolException(name + " 超出整数范围");
        }
        return value.intValue();
    }

    private record Resolution(
            GroupMemberInfoResp target,
            boolean confirmationRequired,
            List<GroupMemberInfoResp> candidates
    ) {
    }
}
