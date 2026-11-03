package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class EconomyAdminService {

    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;

    @Transactional(transactionManager = "dmwTransactionManager")
    public BigDecimal adjust(
            long actorId,
            long targetUserId,
            String targetNickname,
            BigDecimal amount,
            boolean credit,
            String operationId
    ) {
        operationService.start(
                operationId,
                credit ? "ADMIN_CREDIT" : "ADMIN_DEBIT",
                actorId,
                "管理员调整用户 " + targetUserId + " 的余额"
        );
        profileService.getOrCreateForUpdate(
                targetUserId,
                targetNickname == null ? String.valueOf(targetUserId) : targetNickname
        );
        if (credit) {
            return ledgerService.creditUser(
                    targetUserId,
                    EconomyAccountScope.WALLET,
                    EconomyMoney.positive(amount),
                    operationId,
                    EconomyLedgerReason.ADMIN_ADJUST,
                    actorId,
                    "管理员增加余额"
            ).balance();
        }
        return ledgerService.debitUser(
                targetUserId,
                EconomyAccountScope.WALLET,
                EconomyMoney.positive(amount),
                operationId,
                EconomyLedgerReason.ADMIN_ADJUST,
                actorId,
                "管理员扣减余额"
        ).balance();
    }
}
