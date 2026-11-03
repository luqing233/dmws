package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EconomyUserProfileService {

    private final EconomyUserProfileRepository profileRepository;
    private final EconomyUserProfileProvisioner profileProvisioner;

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public EconomyUserProfile getOrCreateForUpdate(long userId, String nickname) {
        if (profileRepository.findById(userId).isEmpty()) {
            try {
                profileProvisioner.ensureProfile(userId, nickname);
            } catch (DataIntegrityViolationException ignored) {
                // Another request won the unique-key race. The profile is now available.
            }
        }
        EconomyUserProfile profile = profileRepository.findForUpdate(userId)
                .orElseThrow(() -> new EconomyException("PROFILE_MISSING", "用户经济档案不存在"));
        updateNickname(profile, nickname);
        return profile;
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public EconomyUserProfile get(long userId) {
        return profileRepository.findById(userId).orElseGet(() -> create(userId, ""));
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public boolean hasRegisteredGroup(long userId) {
        return profileRepository.findById(userId)
                .map(EconomyUserProfile::getRegisterGroupId)
                .isPresent();
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public void rememberGroup(
            long userId,
            String nickname,
            long groupId,
            String groupName
    ) {
        if (groupId <= 0) {
            return;
        }
        if (profileRepository.findById(userId).isEmpty()) {
            try {
                profileProvisioner.ensureProfile(userId, nickname);
            } catch (DataIntegrityViolationException ignored) {
                // Another request created the profile concurrently.
            }
        }
        EconomyUserProfile profile = profileRepository.findForUpdate(userId)
                .orElseThrow(() -> new EconomyException("PROFILE_MISSING", "用户经济档案不存在"));
        updateNickname(profile, nickname);
        if (profile.getRegisterGroupId() == null) {
            profile.setRegisterGroupId(groupId);
            profile.setRegisterGroupName(normalizeGroupName(groupName));
        }
    }

    private EconomyUserProfile create(long userId, String nickname) {
        EconomyUserProfile profile = new EconomyUserProfile();
        profile.setUserId(userId);
        profile.setNickname(normalizeNickname(nickname));
        return profile;
    }

    private void updateNickname(EconomyUserProfile profile, String nickname) {
        String normalized = normalizeNickname(nickname);
        if (!normalized.isBlank() && !normalized.equals(profile.getNickname())) {
            profile.setNickname(normalized);
        }
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null) {
            return "";
        }
        String trimmed = nickname.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    private String normalizeGroupName(String groupName) {
        if (groupName == null) {
            return "";
        }
        String trimmed = groupName.trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
    }
}
