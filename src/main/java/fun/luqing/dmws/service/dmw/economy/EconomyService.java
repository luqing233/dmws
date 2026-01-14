/*
package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyBalanceRecord;
import fun.luqing.dmws.enums.AccountContext;
import fun.luqing.dmws.repository.dmw.economy.EconomyBalanceRecordRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EconomyService {

    private final EconomyBalanceRecordRepository recordRepository;

    */
/**
     * 根据给定的账户上下文和ID获取或创建一个经济余额记录。
     * 如果存在具有相同上下文和ID的记录，则返回该记录；否则，创建一个新的余额为0.0的记录并保存到数据库中。
     *
     * @param context 账户上下文，例如钱包（WALLET）或银行（BANK）
     * @param id      与特定用户相关的唯一标识符
     * @return 返回找到的或者新创建的EconomyBalanceRecord对象
     *//*

    @Transactional
    public EconomyBalanceRecord getOrCreateAccount(AccountContext context, Long id) {
        EconomyBalanceRecordId recordId = new EconomyBalanceRecordId();
        recordId.setContext(context);
        recordId.setId(id);

        return recordRepository.findById(recordId)
                .orElseGet(() -> {
                    EconomyBalanceRecord record = new EconomyBalanceRecord();
                    record.setId(recordId);
                    record.setBalance(0.0);
                    return recordRepository.save(record);
                });
    }

    */
/**
     * 查询钱包和银行余额
     *
     * @param userId 用户ID
     * @return ConsumeResult，包含钱包余额和银行余额
     *//*

    @Transactional
    public ConsumeResult getBalances(Long userId) {
        EconomyBalanceRecord wallet = getOrCreateAccount(AccountContext.WALLET, userId);
        EconomyBalanceRecord bank = getOrCreateAccount(AccountContext.BANK, userId);

        return new ConsumeResult(true, wallet.getBalance(), bank.getBalance());
    }


    */
/**
     * 将指定金额从用户的钱包账户转移到银行账户。
     *
     * @param userId 用户ID
     * @param amount 要存款的金额，必须大于0
     * @return 存款后的银行账户余额
     * @throws IllegalArgumentException 如果存款金额小于或等于0
     * @throws IllegalStateException 如果钱包余额不足
     *//*

    @Transactional
    public double deposit(Long userId, double amount) {
        if (amount <= 0) throw new IllegalArgumentException("存款金额必须大于0");

        EconomyBalanceRecord wallet = getOrCreateAccount(AccountContext.WALLET, userId);
        if (wallet.getBalance() < amount) {
            throw new IllegalStateException("钱包余额不足");
        }

        EconomyBalanceRecord bank = getOrCreateAccount(AccountContext.BANK, userId);

        // 转账
        wallet.setBalance(wallet.getBalance() - amount);
        bank.setBalance(bank.getBalance() + amount);

        recordRepository.save(wallet);
        recordRepository.save(bank);

        return bank.getBalance();
    }

    */
/**
     * 从用户的银行账户中取出指定金额并存入钱包。
     *
     * @param userId 用户ID
     * @param amount 取款金额，必须大于0
     * @return 钱包中的余额
     * @throws IllegalArgumentException 如果取款金额小于或等于0
     * @throws IllegalStateException 如果银行余额不足
     *//*

    @Transactional
    public double withdraw(Long userId, double amount) {
        if (amount <= 0) throw new IllegalArgumentException("取款金额必须大于0");

        EconomyBalanceRecord bank = getOrCreateAccount(AccountContext.BANK, userId);
        if (bank.getBalance() < amount) {
            throw new IllegalStateException("银行余额不足");
        }

        EconomyBalanceRecord wallet = getOrCreateAccount(AccountContext.WALLET, userId);

        // 转账
        bank.setBalance(bank.getBalance() - amount);
        wallet.setBalance(wallet.getBalance() + amount);

        recordRepository.save(bank);
        recordRepository.save(wallet);

        return wallet.getBalance();
    }

    */
/**
     * 转账：从一个用户的银行账户转到另一个用户的银行账户
     *
     * @param fromUserId 转出用户ID
     * @param toUserId   转入用户ID
     * @param amount     转账金额
     * @return 转出用户银行账户余额
     *//*

    @Transactional
    public double transfer(Long fromUserId, Long toUserId, double amount) {
        if (amount <= 0) throw new IllegalArgumentException("转账金额必须大于 0");
        if (fromUserId.equals(toUserId)) throw new IllegalArgumentException("不能给自己转账");

        // 获取源账户（自动开户）
        EconomyBalanceRecord fromBank = getOrCreateAccount(AccountContext.BANK, fromUserId);

        if (fromBank.getBalance() < amount) {
            throw new IllegalStateException("银行余额不足，无法转账");
        }
        // 获取目标账户（自动开户）
        EconomyBalanceRecord toBank = getOrCreateAccount(AccountContext.BANK, toUserId);
        // 扣款 + 增加目标余额
        fromBank.setBalance(fromBank.getBalance() - amount);
        toBank.setBalance(toBank.getBalance() + amount);
        // 保存数据库
        recordRepository.save(fromBank);
        recordRepository.save(toBank);

        return fromBank.getBalance();
    }

    */
/**
     * 从用户的钱包和银行账户中扣除指定金额。
     *
     * @param userId 用户ID
     * @param amount 要消费的金额，必须大于0
     * @return ConsumeResult对象，包含消费是否成功、钱包余额和银行余额
     * @throws IllegalArgumentException 如果消费金额小于或等于0
     * @throws IllegalStateException 如果钱包和银行余额不足，无法完成消费
     *//*

    @Transactional
    public ConsumeResult consume(Long userId, double amount) {
        if (amount <= 0) throw new IllegalArgumentException("消费金额必须大于0");

        EconomyBalanceRecord wallet = getOrCreateAccount(AccountContext.WALLET, userId);
        EconomyBalanceRecord bank = getOrCreateAccount(AccountContext.BANK, userId);

        double walletBalance = wallet.getBalance();
        double bankBalance = bank.getBalance();

        // 1. 先扣钱包
        wallet.setBalance(walletBalance - amount);
        if (wallet.getBalance() >= 0) {
            // 钱包足够扣，银行不动
            recordRepository.save(wallet);
            return new ConsumeResult(true, wallet.getBalance(), bank.getBalance());
        }

        // 2. 钱包不足，从银行补足
        double deficit = -wallet.getBalance(); // 钱包不足部分
        if (bankBalance >= deficit) {
            // 银行可以补足
            wallet.setBalance(0.0); // 扣完钱包
            bank.setBalance(bankBalance - deficit); // 扣银行
            recordRepository.save(wallet);
            recordRepository.save(bank);
            return new ConsumeResult(true, wallet.getBalance(), bank.getBalance());
        } else {
            // 银行余额不足，消费失败，回滚事务
            throw new IllegalStateException("钱包和银行余额不足，无法完成消费");
        }
    }

}
*/
