package fun.luqing.dmws.entity.economy;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Entity
@Table(name = "economy_balance_record")
@IdClass(EconomyBalanceRecord.IdClass.class)
@Getter
@Setter
public class EconomyBalanceRecord {

    @Id
    @Column(name = "context", nullable = false, length = 255)
    private String context;

    @Id
    @Column(name = "currency", nullable = false, length = 255)
    private String currency;

    @Id
    @Column(name = "uuid", nullable = false, length = 255)
    private String uuid;

    @Column(name = "balance", nullable = false)
    private double balance;

    @Column(name = "latest", nullable = false)
    private long latest;


    // ====== Composite Key Class ======
    public static class IdClass implements Serializable {
        private String context;
        private String currency;
        private String uuid;

        public IdClass() {}

        public IdClass(String context, String currency, String uuid) {
            this.context = context;
            this.currency = currency;
            this.uuid = uuid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            IdClass idClass = (IdClass) o;

            if (!context.equals(idClass.context)) return false;
            if (!currency.equals(idClass.currency)) return false;
            return uuid.equals(idClass.uuid);
        }

        @Override
        public int hashCode() {
            int result = context.hashCode();
            result = 31 * result + currency.hashCode();
            result = 31 * result + uuid.hashCode();
            return result;
        }
    }
}
