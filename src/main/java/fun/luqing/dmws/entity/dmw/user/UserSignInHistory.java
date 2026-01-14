package fun.luqing.dmws.entity.dmw.user;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_sign_in_history",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "date"}))
@Data
public class UserSignInHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private LocalDateTime signTime;

    @PrePersist
    protected void onCreate() {
        this.signTime = LocalDateTime.now();
    }

    @Column(nullable = false)
    private Double BankEarnings;

    @Column(nullable = false)
    private Double SignEarnings;

    @Column(nullable = false)
    private int OldSignNumber;

}

