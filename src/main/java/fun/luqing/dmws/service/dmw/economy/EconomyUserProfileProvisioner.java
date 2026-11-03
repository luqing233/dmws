package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EconomyUserProfileProvisioner {

    private final EconomyUserProfileRepository profileRepository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            transactionManager = "dmwTransactionManager"
    )
    public void ensureProfile(long userId, String nickname) {
        if (profileRepository.findById(userId).isPresent()) {
            return;
        }
        EconomyUserProfile profile = new EconomyUserProfile();
        profile.setUserId(userId);
        profile.setNickname(normalize(nickname));
        profileRepository.saveAndFlush(profile);
    }

    private String normalize(String nickname) {
        if (nickname == null) {
            return "";
        }
        String value = nickname.trim();
        return value.length() <= 80 ? value : value.substring(0, 80);
    }
}
