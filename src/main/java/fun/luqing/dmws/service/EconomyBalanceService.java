package fun.luqing.dmws.service;

import fun.luqing.dmws.entity.economy.EconomyBalanceRecord;
import fun.luqing.dmws.repository.economy.EconomyBalanceRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EconomyBalanceService {

    private final EconomyBalanceRecordRepository repository;

    /**
     * 查询余额
     */
    public double getBalance(String context, String currency, long id) {
        String uuid="u"+id;
        return repository.findByContextAndCurrencyAndUuid(context, currency, uuid)
                .map(EconomyBalanceRecord::getBalance)
                .orElse(0.0);
    }

    /**
     * 新增账户记录
     * 若已存在则返回 false
     */
    public boolean addRecord(String context, String currency, long id, double balance) {
        String uuid="u"+id;
        Optional<EconomyBalanceRecord> existing = repository.findByContextAndCurrencyAndUuid(context, currency, uuid);
        if (existing.isPresent()) {
            return false; // 已存在
        }

        EconomyBalanceRecord record = new EconomyBalanceRecord();
        record.setContext(context);
        record.setCurrency(currency);
        record.setUuid(uuid);
        record.setBalance(balance);
        record.setLatest(System.currentTimeMillis());
        repository.save(record);
        return true;
    }

    /**
     * 修改余额（覆盖式更新）
     */
    public boolean updateBalance(String context, String currency, long id, double newBalance) {
        String uuid="u"+id;
        Optional<EconomyBalanceRecord> recordOpt = repository.findByContextAndCurrencyAndUuid(context, currency, uuid);
        if (recordOpt.isPresent()) {
            EconomyBalanceRecord record = recordOpt.get();
            record.setBalance(newBalance);
            record.setLatest(System.currentTimeMillis());
            repository.save(record);
            return true;
        }
        return false; // 不存在
    }

    /**
     * 扣款（余额不足返回 false）
     */
    public boolean deductBalance(String context, String currency, long id, double amount) {
        String uuid="u"+id;
        Optional<EconomyBalanceRecord> recordOpt = repository.findByContextAndCurrencyAndUuid(context, currency, uuid);
        if (recordOpt.isEmpty()) {
            return false; // 账户不存在
        }

        EconomyBalanceRecord record = recordOpt.get();
        if (record.getBalance() < amount) {
            return false; // 余额不足
        }

        record.setBalance(record.getBalance() - amount);
        record.setLatest(System.currentTimeMillis());
        repository.save(record);
        return true;
    }

    /**
     * 删除记录
     */
    public boolean deleteRecord(String context, String currency, long id) {
        String uuid="u"+id;
        Optional<EconomyBalanceRecord> recordOpt = repository.findByContextAndCurrencyAndUuid(context, currency, uuid);
        if (recordOpt.isPresent()) {
            repository.delete(recordOpt.get());
            return true;
        }
        return false;
    }

    /**
     * 查询所有记录
     */
    public List<EconomyBalanceRecord> findAll() {
        return repository.findAll();
    }

    /**
     * 根据 context 或 currency 查询
     */
    public List<EconomyBalanceRecord> findByContextOrCurrency(String context, String currency) {
        return repository.findAll().stream()
                .filter(r -> (context == null || r.getContext().equals(context)) &&
                        (currency == null || r.getCurrency().equals(currency)))
                .toList();
    }
}
