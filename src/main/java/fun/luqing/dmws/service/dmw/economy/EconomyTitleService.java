package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyTitleDefinition;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserTitle;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyTitleDefinitionRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserTitleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EconomyTitleService {

    private final EconomyTitleDefinitionRepository titleRepository;
    private final EconomyUserTitleRepository userTitleRepository;
    private final EconomyUserProfileService profileService;
    private final EconomyOperationService operationService;
    private final EconomyLedgerService ledgerService;

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyTitleDefinition> shop() {
        return titleRepository.findAllByEnabledTrueOrderByPriceAsc();
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyUserTitle> owned(long userId) {
        return userTitleRepository.findByUserIdOrderByAcquiredAtAsc(userId);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public void buy(long userId, String nickname, String titleCode, String operationId) {
        EconomyTitleDefinition title = titleRepository.findById(titleCode)
                .filter(EconomyTitleDefinition::getEnabled)
                .orElseThrow(() -> new EconomyException("TITLE_NOT_FOUND", "称号不存在或已下架"));
        if (userTitleRepository.existsByUserIdAndTitleCode(userId, titleCode)) {
            throw new EconomyException("TITLE_OWNED", "你已经拥有该称号");
        }

        operationService.start(operationId, "TITLE_PURCHASE", userId, "购买称号 " + titleCode);
        profileService.getOrCreateForUpdate(userId, nickname);
        ledgerService.debitUserWithBankFallback(
                userId,
                EconomyMoney.positive(title.getPrice()),
                operationId,
                EconomyLedgerReason.TITLE_PURCHASE,
                userId,
                "购买称号: " + title.getName()
        );
        grant(userId, titleCode);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public String activate(long userId, String nickname, String titleCode) {
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        if (titleCode == null || titleCode.isBlank() || "0".equals(titleCode)) {
            profile.setActiveTitleCode(null);
            return "已卸下当前称号";
        }
        EconomyUserTitle title = userTitleRepository.findByUserIdAndTitleCode(userId, titleCode)
                .orElseThrow(() -> new EconomyException("TITLE_NOT_OWNED", "你还没有该称号"));
        profile.setActiveTitleCode(title.getTitleCode());
        return "已切换称号: " + displayName(userId);
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public boolean grant(long userId, String titleCode) {
        EconomyTitleDefinition title = titleRepository.findById(titleCode)
                .orElseThrow(() -> new EconomyException("TITLE_NOT_FOUND", "称号不存在: " + titleCode));
        Optional<EconomyUserTitle> existing =
                userTitleRepository.findByUserIdAndTitleCode(userId, titleCode);
        if (existing.isPresent()) {
            return false;
        }
        EconomyUserTitle userTitle = new EconomyUserTitle();
        userTitle.setUserId(userId);
        userTitle.setTitleCode(title.getCode());
        userTitleRepository.save(userTitle);
        return true;
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public String displayName(long userId) {
        return profileService.get(userId).getActiveTitleCode() == null
                ? "无"
                : titleRepository.findById(profileService.get(userId).getActiveTitleCode())
                .map(EconomyTitleDefinition::getName)
                .orElse("无");
    }

    public String titleName(String code) {
        return titleRepository.findById(code)
                .map(EconomyTitleDefinition::getName)
                .orElse(code);
    }

    public EconomyTitleDefinition resolve(String codeOrName) {
        return titleRepository.findById(codeOrName)
                .or(() -> titleRepository.findFirstByEnabledTrueAndName(codeOrName))
                .filter(EconomyTitleDefinition::getEnabled)
                .orElseThrow(() -> new EconomyException("TITLE_NOT_FOUND", "称号不存在: " + codeOrName));
    }
}
