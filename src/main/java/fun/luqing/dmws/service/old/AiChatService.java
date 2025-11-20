package fun.luqing.dmws.service.old;

import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.entity.economy.EconomyBalanceRecord;
import fun.luqing.dmws.enums.AiStatus;
import fun.luqing.dmws.repository.economy.EconomyBalanceRecordRepository;
import fun.luqing.dmws.common.utils.AiResponse;
import fun.luqing.dmws.common.utils.AiUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;

@Slf4j
@Service
public class AiChatService {
    @Resource
    private AiUtil aiUtil;
    @Resource
    private ConfigManager configManager;
    @Resource
    private EconomyBalanceRecordRepository economyRepo;

    private static final String CONTEXT = "global-economy";
    private static final String CURRENCY = "hy-gold";

    /**
     * 统一调用AI进行对话
     *
     * @param userId   用户ID
     * @param nickname 用户昵称
     * @param message  用户消息
     * @return AI回复内容
     */
    public AiResponse talkWithAi(long userId, String nickname, String message) {
        if (message == null || message.isBlank()) {
            message = "（该用户什么都没有说）";
        }

        // 加时间戳，记录格式一致
        String timeString = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String formattedMessage = timeString + " " + nickname + ": " + message;

        double tokenRate = configManager.getDmwConfig().getToken_exchange_rate();

        // 如果兑换系数为 0，跳过余额检测与扣费
        if (tokenRate == 0) {
            AiResponse response = aiUtil.chat(userId, formattedMessage);
            log.info("[AI回复][{}] 无需扣费，回复：{}", nickname, response.response());
            return response;
        }

        // 检查并扣除余额
        Optional<EconomyBalanceRecord> recordOpt = checkAndDeductBalance(userId, tokenRate, formattedMessage);
        if (recordOpt.isEmpty()) {
            return AiResponse.error("账户不存在或余额不足，无法使用AI服务。");
        }

        return aiUtil.chat(userId, formattedMessage);
    }

    /**
     * 检查用户余额是否足够使用AI，并在成功时扣除费用
     *
     * @param userId           用户ID
     * @param tokenRate        令牌兑换率
     * @param formattedMessage 格式化的消息
     * @return 用户余额记录（如果存在且余额足够）
     */
    private Optional<EconomyBalanceRecord> checkAndDeductBalance(long userId, double tokenRate, String formattedMessage) {
        String uuid = "u" + userId;
        Optional<EconomyBalanceRecord> recordOpt = economyRepo.findByContextAndCurrencyAndUuid(CONTEXT, CURRENCY, uuid);

        if (recordOpt.isEmpty()) {
            log.warn("[AI对话] 用户 {} 没有账户记录", userId);
            return Optional.empty();
        }

        EconomyBalanceRecord record = recordOpt.get();
        if (record.getBalance() <= 1) {
            log.warn("[AI对话] 用户 {} 余额不足", userId);
            return Optional.empty();
        }

        // 调用 AI
        AiResponse response = aiUtil.chat(userId, formattedMessage);
        if (response.status() == AiStatus.SUCCESS) {
            double cost = response.total_tokens() * tokenRate;
            double newBalance = record.getBalance() - cost;
            record.setBalance(Math.max(newBalance, 0));
            record.setLatest(System.currentTimeMillis());
            economyRepo.save(record);
            log.info("[AI对话] 用户 {} 扣费 {}，新余额 {}", userId, cost, newBalance);
        }

        return Optional.of(record);
    }

    /**
     * 检查用户余额是否足够使用AI
     *
     * @param userId 用户ID
     * @return true=有余额可用，false=余额不足或无账户
     */
    public boolean hasEnoughBalance(long userId) {
        double tokenRate = configManager.getDmwConfig().getToken_exchange_rate();
        if (tokenRate == 0) {
            return true; // 无需扣费则默认可用
        }
        String uuid = "u" + userId;
        Optional<EconomyBalanceRecord> recordOpt = economyRepo.findByContextAndCurrencyAndUuid(CONTEXT, CURRENCY, uuid);
        return recordOpt.isPresent() && recordOpt.get().getBalance() > 1;
    }
}
