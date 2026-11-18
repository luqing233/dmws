package fun.luqing.dmws.service.dmw.economy;

import lombok.Getter;

@Getter
public class FishReviewException extends RuntimeException {
    private final String code;
    private final int status;
    public FishReviewException(String code, String message, int status) {
        super(message); this.code = code; this.status = status;
    }
}
