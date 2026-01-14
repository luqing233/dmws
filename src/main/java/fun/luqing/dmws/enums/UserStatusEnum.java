package fun.luqing.dmws.enums;

import lombok.Getter;

@Getter
public enum UserStatusEnum {

    IDLE(0, "空闲中"),              // 未进行任何活动
    FISHING(1, "钓鱼中"),           // 正在钓鱼
    ROBBING(2, "正在抢劫别人"),      // 发起抢劫
    BEING_ROBBED(3, "被抢劫中"),     // 作为受害者
    HOSPITAL(4, "医院中"),          // 受伤住院
    PRISON(5, "监狱中"),            // 被抓进入牢房
    BUSY(6, "忙碌中"),              // 临时忙，如执行动画/冷却中
    RESTING(8, "休息中");           // 停止某些活动恢复中

    private final int code;
    private final String desc;

    UserStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static UserStatusEnum fromCode(int code) {
        for (UserStatusEnum s : values()) {
            if (s.code == code) return s;
        }
        return null;
    }
}

