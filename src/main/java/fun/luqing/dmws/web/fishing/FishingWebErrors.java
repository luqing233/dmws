package fun.luqing.dmws.web.fishing;

import fun.luqing.dmws.service.dmw.economy.FishReviewException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = FishingWebController.class)
public class FishingWebErrors {
    @ExceptionHandler(FishReviewException.class)
    public ResponseEntity<?> business(FishReviewException error) {
        return ResponseEntity.status(error.getStatus()).body(Map.of("code", error.getCode(), "message", error.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> malformed(Exception error) { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT", "message", "输入格式不正确，请检查表单。")); }
}
