package fun.luqing.dmws.mcp;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.service.dmw.economy.EconomyConditionService;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class EconomyConditionMcpTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EconomyMcpTools identity;
    private final EconomyConditionService conditions;
    private final EconomyPlayerNameService names;

    public ObjectNode execute(String tool, JsonNode args) {
        if (!"dmw_economy_status".equals(tool) && !"dmw_economy_get_location".equals(tool) && !"dmw_economy_discharge".equals(tool) && !"dmw_economy_bail".equals(tool)) {
            throw new McpToolException("未知的状态工具");
        }
        long userId = owner(args);
        try {
            if ("dmw_economy_get_location".equals(tool)) {
                var location = conditions.location(userId, "");
                var response = success("当前位置查询成功");
                String playerName = names.resolve(identity.optionalLong(args, "group_id"), userId);
                response.put("display_text", conditions.describeLocation(location, playerName));
                response.putObject("data").put("user_id", userId).put("location", location.location())
                        .put("user_name", playerName)
                        .put("location_name", location.locationName()).put("hospital_until", timestamp(location.hospitalUntil()))
                        .put("jail_until", timestamp(location.jailUntil()));
                return response;
            }
            if ("dmw_economy_status".equals(tool)) return status(conditions.status(userId, ""), identity.optionalLong(args, "group_id"));
            long groupId = identity.requiredLong(args, "group_id");
            if (groupId <= 0) throw new McpToolException("group_id 必须为正整数");
            var bot = identity.economyBot();
            var member = identity.requireGroupMember(bot, groupId, userId);
            String nickname = names.member(bot, groupId, member);
            if ("dmw_economy_discharge".equals(tool)) {
                var receipt = conditions.dischargeReceipt(userId, nickname, identity.messageOperation("discharge", args, userId, "", "discharge", identity.requestId(args)));
                var response = status(receipt.status(), groupId);
                response.put("message", receipt.message()).put("display_text", receipt.message());
                ((ObjectNode) response.path("data")).put("medical_debt", EconomyMoney.format(receipt.medicalDebt()))
                        .put("discharged_now", receipt.dischargedNow());
                return response;
            }
            long target = identity.requiredLong(args, "target_user_id");
            if (target <= 0) throw new McpToolException("target_user_id 必须为正整数");
            var targetMember = identity.requireGroupMember(bot, groupId, target);
            String targetName = names.member(bot, groupId, targetMember);
            String request = identity.requestId(args);
            if (!args.path("confirm").isBoolean() || !args.path("confirm").asBoolean()) {
                var state = conditions.status(target, "");
                ObjectNode result = status(state, groupId);
                ((ObjectNode) result.path("data")).put("user_name", targetName);
                result.put("requires_confirmation", state.jailUntil() != null);
                String quote = state.jailUntil() == null ? targetName + " 当前未服刑，无需保释"
                        : targetName + " 的保释费：" + EconomyMoney.format(state.bailFee()) + " 金币（尚未支付）";
                result.put("message", quote).put("display_text", quote);
                return result;
            }
            var receipt = conditions.bail(userId, nickname, target, identity.messageOperation("bail", args, userId, "", "bail", request));
            ObjectNode result = success("保释成功，犯罪值保留");
            result.put("display_text", "保释成功，" + targetName + " 已出狱\n已支付保释费：" + EconomyMoney.format(receipt.fee()) + " 金币");
            result.putObject("data").put("helper_user_id", receipt.helperId()).put("target_user_id", receipt.prisonerId())
                    .put("helper_name", nickname).put("target_name", targetName)
                    .put("fee", EconomyMoney.format(receipt.fee())).put("crime", receipt.prisonerCrime())
                    .put("wallet_balance", EconomyMoney.format(receipt.walletBalance()))
                    .put("bank_balance", EconomyMoney.format(receipt.bankBalance()));
            return result;
        } catch (EconomyException e) {
            return MAPPER.createObjectNode().put("ok", false).put("code", e.getCode()).put("message", e.getMessage());
        }
    }

    private long owner(JsonNode args) {
        Long specified = identity.optionalLong(args, "user_id");
        if (args != null && args.has("user_id") && (specified == null || specified <= 0)) throw new McpToolException("user_id 必须为正整数");
        return specified == null ? identity.botUserId() : specified;
    }

    private ObjectNode status(EconomyConditionService.Status status, Long groupId) {
        ObjectNode result = success("玩家状态查询/结算成功");
        var data = result.putObject("data");
        data.put("user_id", status.userId()).put("user_name", names.resolve(groupId, status.userId()))
                .put("crime", status.crime()).put("injury", status.injury())
                .put("hospital_until", timestamp(status.hospitalUntil())).put("jail_until", timestamp(status.jailUntil()))
                .put("current_hospital_fee", EconomyMoney.format(status.currentHospitalFee()))
                .put("bail_fee", EconomyMoney.format(status.bailFee()))
                .put("last_hospital_fee", status.lastHospitalFee() == null ? null : EconomyMoney.format(status.lastHospitalFee()))
                .put("last_medical_loan", status.lastMedicalLoan() == null ? null : EconomyMoney.format(status.lastMedicalLoan()))
                .put("last_discharged_at", timestamp(status.lastDischargedAt()));
        return result;
    }
    private String timestamp(LocalDateTime value) { return value == null ? null : value.toString(); }
    private ObjectNode success(String message) { return MAPPER.createObjectNode().put("ok", true).put("code", "OK").put("message", message); }
}
