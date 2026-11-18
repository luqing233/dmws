package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCast.Status;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishingCastRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishingCastRepository.CastKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "dmwTransactionManager")
public class EconomyFishingQueue {
    private final EconomyFishingCastRepository casts;
    private final EconomyFishingTextService texts;

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<CastKey> due(LocalDateTime now) {
        return casts.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscOperationIdAsc(
                Status.WAITING, now, PageRequest.of(0, 50));
    }

    public void recordFailure(String operationId, LocalDateTime now, String reason) {
        casts.lock(operationId).filter(cast -> cast.getStatus() == Status.WAITING
                && !cast.getNextAttemptAt().isAfter(now)).ifPresent(cast -> {
            cast.setFailures(cast.getFailures() + 1);
            if (reason == null && cast.getFailures() < 3) {
                cast.setNextAttemptAt(now.plusSeconds(5L * cast.getFailures()));
                return;
            }
            cast.setReceipt(texts.message(reason == null ? "REEL_FAILED" : "REEL_REJECTED",
                    java.util.Map.of("reason", EconomyFishingText.line(reason)), cast.getRandomSeed()));
            cast.setStatus(cast.getGroupId() != null && cast.getMessageId() != null ? Status.READY : Status.SENT);
        });
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<CastKey> ready(long botId) {
        return casts.findByBotIdAndStatusOrderByDueAtAscOperationIdAsc(botId, Status.READY, PageRequest.of(0, 10));
    }

    public Notice claim(String operationId, long botId) {
        return casts.lock(operationId).filter(cast -> cast.getStatus() == Status.READY && cast.getBotId() == botId)
                .map(cast -> {
                    cast.setStatus(Status.SENDING);
                    return new Notice(cast.getGroupId(), cast.getMessageId(), cast.getReceipt());
                }).orElse(null);
    }

    public void finish(String operationId, Status status) {
        casts.lock(operationId).filter(cast -> cast.getStatus() == Status.SENDING)
                .ifPresent(cast -> cast.setStatus(status));
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public String status(long userId) {
        return casts.findFirstByUserIdOrderByCastAtDescOperationIdDesc(userId)
                .map(cast -> cast.getStatus() == Status.WAITING
                        ? texts.message("WAITING", java.util.Map.of(), cast.getRandomSeed()) : cast.getReceipt())
                .orElseGet(() -> texts.message("NO_CAST", java.util.Map.of(), EconomyFishingRandom.newSeed()));
    }

    public record Notice(long groupId, int messageId, String receipt) { }
}
