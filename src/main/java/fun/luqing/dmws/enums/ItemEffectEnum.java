package fun.luqing.dmws.enums;

import lombok.Getter;

@Getter
public enum ItemEffectEnum {
    HP(0, "血量"),
    DODGE(1, "闪避"),
    DEFENSE(2, "防御力"),
    ATTACK(3, "攻击力");

    private final int code;
    private final String desc;

    ItemEffectEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ItemEffectEnum fromCode(int code) {
        for (ItemEffectEnum e : values()) {
            if (e.code == code) return e;
        }
        return null;
    }
}
