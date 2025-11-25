package fun.luqing.dmws.entity.dmw;

import fun.luqing.dmws.common.converter.PlaceEnumConverter;
import fun.luqing.dmws.enums.PlaceEnum;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_status")
@Data
public class UserStatus {

    @Id
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Convert(converter = PlaceEnumConverter.class)
    private PlaceEnum place;

    private Integer recoveryTime;

    @Column(name = "start_time")
    private LocalDateTime startTime;

}
