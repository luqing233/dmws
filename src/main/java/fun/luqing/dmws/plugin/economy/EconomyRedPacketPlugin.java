package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyRedPacket;
import fun.luqing.dmws.enums.EconomyRedPacketKind;
import fun.luqing.dmws.service.dmw.economy.EconomyRedPacketService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyRedPacketPlugin {

    private final EconomyRedPacketService redPacketService;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"发红包"})
    @CommandInfo(startWith = "发红包", desc = "发红包 <金额> <个数> [随机] [口令]")
    public void create(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "发红包");
            if (parts.length < 2) {
                throw new EconomyException("ARGUMENT_REQUIRED", "格式: 发红包 金额 个数 [随机] [口令]");
            }
            BigDecimal amount = EconomyMoney.parse(parts[0]);
            int count = EconomyCommandSupport.quantity(parts[1], 1);
            EconomyRedPacketKind kind = EconomyRedPacketKind.FIXED;
            String password = null;
            if (parts.length > 2) {
                if ("随机".equals(parts[2]) || "random".equalsIgnoreCase(parts[2])) {
                    kind = EconomyRedPacketKind.RANDOM;
                    if (parts.length > 3) {
                        password = parts[3];
                    }
                } else {
                    password = parts[2];
                }
            }
            long id = redPacketService.create(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    event.getGroupId(),
                    amount,
                    count,
                    kind,
                    password,
                    EconomyCommandSupport.operationId("redpacket-create", event)
            );
            return "红包发送成功，编号: " + id + "\n领取方式: 抢红包 " + id
                    + (password == null ? "" : " " + password);
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"抢红包", "领红包", "收红包"})
    @CommandInfo(startWith = "抢红包", desc = "抢红包 <编号> [口令]")
    public void claim(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "抢红包", "领红包", "收红包");
            if (parts.length == 0) {
                String operationId = EconomyCommandSupport.operationId("redpacket-claim-newest", event);
                for (EconomyRedPacket packet : redPacketService.active(event.getGroupId())) {
                    if (packet.getSenderId().equals(event.getUserId())) {
                        continue;
                    }
                    try {
                        EconomyRedPacketService.ClaimResult result = redPacketService.claim(
                                event.getUserId(),
                                EconomyCommandSupport.nickname(event),
                                event.getGroupId(),
                                packet.getId(),
                                null,
                                operationId + ":" + packet.getId()
                        );
                        return "领取成功: " + EconomyMoney.format(result.amount())
                                + "\n红包剩余: " + result.remainingCount() + " 个"
                                + "\n钱包余额: " + EconomyMoney.format(result.walletBalance());
                    } catch (EconomyException ignored) {
                        // This packet is already claimed, password protected, expired, or unavailable.
                    }
                }
                throw new EconomyException("NO_RED_PACKET", "没有可领取的红包");
            }
            long packetId;
            try {
                packetId = Long.parseLong(parts[0]);
            } catch (NumberFormatException exception) {
                throw new EconomyException("INVALID_PACKET_ID", "红包编号格式不正确");
            }
            String password = parts.length > 1 ? parts[1] : null;
            EconomyRedPacketService.ClaimResult result = redPacketService.claim(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    event.getGroupId(),
                    packetId,
                    password,
                    EconomyCommandSupport.operationId("redpacket-claim", event)
            );
            return "领取成功: " + EconomyMoney.format(result.amount())
                    + "\n红包剩余: " + result.remainingCount() + " 个"
                    + "\n钱包余额: " + EconomyMoney.format(result.walletBalance());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(红包列表|红包)$")
    @CommandInfo(cmd = "红包列表", desc = "查看本群可领取红包")
    public void list(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyRedPacket> packets = redPacketService.active(event.getGroupId());
            if (packets.isEmpty()) {
                return "本群暂时没有可领取红包";
            }
            StringBuilder builder = new StringBuilder("本群红包");
            for (EconomyRedPacket packet : packets) {
                builder.append("\n#").append(packet.getId())
                        .append(" 剩余 ").append(packet.getRemainingCount()).append("/")
                        .append(packet.getTotalCount()).append(" 个，")
                        .append(EconomyMoney.format(packet.getRemainingAmount()))
                        .append(packet.getPassword() == null ? "" : "（口令红包）");
            }
            return builder.toString();
        });
    }
}
