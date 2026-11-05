package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "economy_player_condition", indexes = {
        @Index(name = "idx_condition_hospital_until", columnList = "hospital_until"),
        @Index(name = "idx_condition_jail_until", columnList = "jail_until")})
public class EconomyPlayerCondition {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(nullable = false)
    private int crime = 0;
    @Column(name = "crime_decay_on")
    private LocalDate crimeDecayOn;
    @Column(name = "crime_relief_on")
    private LocalDate crimeReliefOn;
    @Column(name = "crime_relief_used", nullable = false, columnDefinition = "int default 0")
    private int crimeReliefUsed = 0;
    @Column(nullable = false)
    private int injury = 0;
    @Column(name = "hospital_since")
    private LocalDateTime hospitalSince;
    @Column(name = "hospital_until")
    private LocalDateTime hospitalUntil;
    @Column(name = "hospital_session", length = 36)
    private String hospitalSession;
    @Column(name = "diagnosis_fee", precision = 19, scale = 2)
    private BigDecimal diagnosisFee;
    @Column(name = "hourly_fee", precision = 19, scale = 2)
    private BigDecimal hourlyFee;
    @Column(name = "jail_until")
    private LocalDateTime jailUntil;
    @Column(name = "last_hospital_fee", precision = 19, scale = 2)
    private BigDecimal lastHospitalFee;
    @Column(name = "last_medical_loan", precision = 19, scale = 2)
    private BigDecimal lastMedicalLoan;
    @Column(name = "last_discharged_at")
    private LocalDateTime lastDischargedAt;
}
