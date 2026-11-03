package fun.luqing.dmws.common.economy;

import lombok.Getter;

@Getter
public class EconomyException extends RuntimeException {

    private final String code;

    public EconomyException(String code, String message) {
        super(message);
        this.code = code;
    }
}
