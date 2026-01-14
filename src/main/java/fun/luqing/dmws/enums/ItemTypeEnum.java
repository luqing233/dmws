package fun.luqing.dmws.enums;

import lombok.Getter;

@Getter
public enum ItemTypeEnum {
    EQUIPMENT(0, "装备"),
    FOOD(1, "食用物品");

    private final int code;
    private final String desc;

    ItemTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ItemTypeEnum fromCode(int code) {
        for (ItemTypeEnum t : values()) {
            if (t.code == code) return t;
        }
        return null;
    }
}
