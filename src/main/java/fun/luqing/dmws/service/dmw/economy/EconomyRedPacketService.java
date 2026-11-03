package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyRedPacket;
import fun.luqing.dmws.entity.dmw.economy.EconomyRedPacketClaim;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.enums.EconomyRedPacketKind;
import fun.luqing.dmws.enums.EconomyRedPacketStatus;
import fun.luqing.dmws.repository.dmw.economy.EconomyRedPacketClaimRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyRedPacketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class EconomyRedPacketService {

    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyRedPacketRepository packetRepository;
    private final EconomyRedPacketClaimRepository claimRepository;

    @Transactional(transactionManager = "dmwTransactionManager")
    public long create(
            long senderId,
            String nickname,
            long groupId,
            BigDecimal totalAmount,
            int count,
            EconomyRedPacketKind kind,
            String password,
            String operationId
    ) {
        if (count <= 0 || count > 100) {
            throw new EconomyException("INVALID_COUNT", "红包个数必须在 1 到 100 之间");
        }
        BigDecimal total = EconomyMoney.positive(totalAmount);
        BigDecimal minimum = BigDecimal.valueOf(count).multiply(new BigDecimal("0.01"));
        if (total.compareTo(minimum) < 0) {
            throw new EconomyException("INVALID_AMOUNT", "总金额不足以让每个红包至少获得 0.01");
        }

        operationService.start(operationId, "RED_PACKET_CREATE", senderId, "创建红包");
        profileService.getOrCreateForUpdate(senderId, nickname);
        ledgerService.debitUser(
                senderId,
                EconomyAccountScope.WALLET,
                total,
                operationId,
                EconomyLedgerReason.RED_PACKET_CREATE,
                senderId,
                "创建红包"
        );

        EconomyRedPacket packet = new EconomyRedPacket();
        packet.setSenderId(senderId);
        packet.setGroupId(groupId);
        packet.setKind(kind);
        packet.setStatus(EconomyRedPacketStatus.ACTIVE);
        packet.setTotalAmount(total);
        packet.setRemainingAmount(total);
        packet.setTotalCount(count);
        packet.setRemainingCount(count);
        packet.setPassword(password == null || password.isBlank() ? null : password.trim());
        packet.setExpiresAt(LocalDateTime.now().plusHours(properties.getRedPacketExpireHours()));
        return packetRepository.saveAndFlush(packet).getId();
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public ClaimResult claim(
            long userId,
            String nickname,
            long groupId,
            long packetId,
            String password,
            String operationId
    ) {
        EconomyRedPacket packet = packetRepository.findForUpdate(packetId)
                .orElseThrow(() -> new EconomyException("PACKET_NOT_FOUND", "红包不存在"));
        if (!packet.getGroupId().equals(groupId)) {
            throw new EconomyException("PACKET_GROUP_MISMATCH", "该红包不属于当前群");
        }
        if (packet.getSenderId().equals(userId)) {
            throw new EconomyException("PACKET_SELF_CLAIM", "不能领取自己发送的红包");
        }
        if (packet.getStatus() == EconomyRedPacketStatus.EXPIRED) {
            throw new EconomyException("PACKET_EXPIRED", "红包已经过期");
        }
        if (packet.getStatus() == EconomyRedPacketStatus.FINISHED || packet.getRemainingCount() <= 0) {
            throw new EconomyException("PACKET_FINISHED", "红包已经领完");
        }
        if (packet.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new EconomyException("PACKET_EXPIRED", "红包已经过期");
        }
        if (packet.getPassword() != null
                && (password == null || !packet.getPassword().equals(password.trim()))) {
            throw new EconomyException("PACKET_PASSWORD_INVALID", "红包口令不正确");
        }
        if (claimRepository.findByPacketIdAndUserId(packetId, userId).isPresent()) {
            throw new EconomyException("PACKET_ALREADY_CLAIMED", "你已经领取过该红包");
        }

        operationService.start(operationId, "RED_PACKET_CLAIM", userId, "领取红包 " + packetId);
        profileService.getOrCreateForUpdate(userId, nickname);
        BigDecimal amount = nextAmount(packet);

        packet.setRemainingAmount(EconomyMoney.normalize(packet.getRemainingAmount().subtract(amount)));
        packet.setRemainingCount(packet.getRemainingCount() - 1);
        if (packet.getRemainingCount() == 0) {
            packet.setStatus(EconomyRedPacketStatus.FINISHED);
        }
        packetRepository.save(packet);

        EconomyRedPacketClaim claim = new EconomyRedPacketClaim();
        claim.setPacketId(packetId);
        claim.setUserId(userId);
        claim.setAmount(amount);
        claimRepository.save(claim);

        EconomyLedgerService.AccountBalance balance = ledgerService.creditUser(
                userId,
                EconomyAccountScope.WALLET,
                amount,
                operationId,
                EconomyLedgerReason.RED_PACKET_CLAIM,
                userId,
                "领取红包 " + packetId
        );
        return new ClaimResult(packetId, amount, packet.getRemainingCount(), balance.balance());
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyRedPacket> active(long groupId) {
        return packetRepository.findAllByGroupIdAndStatusOrderByIdDesc(
                groupId,
                EconomyRedPacketStatus.ACTIVE
        );
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<Long> duePacketIds() {
        return packetRepository.findAllByStatusAndExpiresAtBefore(
                        EconomyRedPacketStatus.ACTIVE,
                        LocalDateTime.now()
                )
                .stream()
                .map(EconomyRedPacket::getId)
                .toList();
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public void expire(long packetId) {
        EconomyRedPacket packet = packetRepository.findForUpdate(packetId).orElse(null);
        if (packet == null
                || packet.getStatus() != EconomyRedPacketStatus.ACTIVE
                || !packet.getExpiresAt().isBefore(LocalDateTime.now())) {
            return;
        }

        String operationId = "redpacket-expire:" + packetId;
        operationService.start(operationId, "RED_PACKET_EXPIRE", packet.getSenderId(), "红包退款");
        if (packet.getRemainingAmount().signum() > 0) {
            ledgerService.creditUser(
                    packet.getSenderId(),
                    EconomyAccountScope.WALLET,
                    packet.getRemainingAmount(),
                    operationId,
                    EconomyLedgerReason.RED_PACKET_REFUND,
                    packet.getSenderId(),
                    "红包过期退款 " + packetId
            );
        }
        packet.setStatus(EconomyRedPacketStatus.EXPIRED);
        packetRepository.save(packet);
    }

    private BigDecimal nextAmount(EconomyRedPacket packet) {
        if (packet.getRemainingCount() == 1) {
            return EconomyMoney.normalize(packet.getRemainingAmount());
        }

        BigDecimal minimumReserve = BigDecimal.valueOf(packet.getRemainingCount() - 1L)
                .multiply(new BigDecimal("0.01"));
        BigDecimal maximum = packet.getRemainingAmount().subtract(minimumReserve);
        if (maximum.compareTo(new BigDecimal("0.01")) <= 0) {
            return new BigDecimal("0.01");
        }

        if (packet.getKind() == EconomyRedPacketKind.FIXED) {
            BigDecimal average = packet.getRemainingAmount()
                    .divide(BigDecimal.valueOf(packet.getRemainingCount()), 2, RoundingMode.DOWN);
            return average.max(new BigDecimal("0.01")).min(maximum);
        }

        long maxCents = maximum.movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();
        long amountCents = ThreadLocalRandom.current().nextLong(1, maxCents + 1);
        return BigDecimal.valueOf(amountCents, 2);
    }

    public record ClaimResult(
            long packetId,
            BigDecimal amount,
            int remainingCount,
            BigDecimal walletBalance
    ) {
    }
}
