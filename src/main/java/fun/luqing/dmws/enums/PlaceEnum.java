package fun.luqing.dmws.enums;

import lombok.Getter;

@Getter
public enum PlaceEnum {
    HOME(0, "家"),
    HOSPITAL(1, "医院"),
    PRISON(2, "监狱"),
    FISHPOND(3, "鱼塘");

    private final int code;
    private final String desc;

    PlaceEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PlaceEnum fromCode(int code) {
        for (PlaceEnum p : values()) {
            if (p.code == code) return p;
        }
        return null;
    }
}