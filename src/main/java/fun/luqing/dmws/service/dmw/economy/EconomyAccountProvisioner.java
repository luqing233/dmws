package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyAccount;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.repository.dmw.economy.EconomyAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EconomyAccountProvisioner {

    private final EconomyAccountRepository accountRepository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            transactionManager = "dmwTransactionManager"
    )
    public void ensureAccount(
            String accountKey,
            EconomyAccountScope scope,
            Long ownerId
    ) {
        if (accountRepository.findByAccountKeyAndScope(accountKey, scope).isPresent()) {
            return;
        }
        EconomyAccount account = new EconomyAccount();
        account.setAccountKey(accountKey);
        account.setScope(scope);
        account.setOwnerId(ownerId);
        account.setBalance(EconomyMoney.zero());
        accountRepository.saveAndFlush(account);
    }
}
