package fun.luqing.dmws.entity.dmw.hospital;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Data
@Entity
@Table(name = "hospitalization_history")
public class HospitalizationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "admit_time", nullable = false)
    private LocalDateTime admitTime;

    @Column(name = "discharge_time")
    private LocalDateTime dischargeTime;

    @Column(name = "hospital_fee")
    private BigDecimal hospitalFee = BigDecimal.ZERO; // 应收

    @Column(name = "paid_fee")
    private BigDecimal paidFee = BigDecimal.ZERO;

}