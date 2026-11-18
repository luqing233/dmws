package fun.luqing.dmws.plugin;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SeTuRequestTests {
    @Test void defaultsToRegularAndParsesNumericOptionNames() {
        var request = SeTuRequest.parse("涩图 tag=风景 夜空 r18=2 num=3");
        assertEquals(List.of("风景", "夜空"), request.tags());
        assertEquals(2, request.r18());
        assertEquals(3, request.num());
        assertEquals(List.of("regular"), request.sizes());
    }

    @Test void preservesMultipleSizesAndCommaSeparatedOptions() {
        var request = SeTuRequest.parse("涩图  风景\t size=original,regular,num=2，r18=0 excludeai=true");
        assertEquals(List.of("original", "regular"), request.sizes());
        assertEquals(2, request.num());
        assertTrue(request.excludeAI());
    }

    @Test void reportsInvalidInputsInsteadOfSilentlyChangingThem() {
        for (String input : List.of("涩图", "涩图 num=2", "涩图 tag=风景 num=x", "涩图 风景 num=6",
                "涩图 风景 r18=3", "涩图 风景 size=other", "涩图 风景 excludeai=maybe", "涩图 风景 foo=1")) {
            assertThrows(IllegalArgumentException.class, () -> SeTuRequest.parse(input), input);
        }
    }
}
