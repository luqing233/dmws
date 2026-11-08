package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyMarketState;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class EconomyMarketProvisioner {
    @PersistenceContext(unitName = "dmw")
    private EntityManager em;

    @Transactional(transactionManager = "dmwTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void ensure() {
        if (em.find(EconomyMarketState.class, 1) == null) {
            var state = new EconomyMarketState();
            byte[] secret = new byte[32];
            new java.security.SecureRandom().nextBytes(secret);
            state.setEntropyKey(java.util.HexFormat.of().formatHex(secret));
            em.persist(state);
            em.flush();
        }
    }
}
