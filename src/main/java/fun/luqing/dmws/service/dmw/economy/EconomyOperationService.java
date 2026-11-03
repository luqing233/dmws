package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.entity.dmw.economy.EconomyOperation;
import fun.luqing.dmws.repository.dmw.economy.EconomyOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EconomyOperationService {

    private final EconomyOperationRepository operationRepository;

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public boolean isProcessed(String operationId) {
        return operationRepository.existsById(operationId);
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public void start(String operationId, String type, Long actorId, String description) {
        if (operationRepository.existsById(operationId)) {
            throw new DuplicateEconomyOperationException(operationId);
        }
        try {
            operationRepository.saveAndFlush(EconomyOperation.create(
                    operationId,
                    type,
                    actorId,
                    description
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateEconomyOperationException(operationId);
        }
    }
}
