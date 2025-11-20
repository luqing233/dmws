package fun.luqing.dmws.service.old;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;


@Service
public class CheckTTSStatus {

    private final RestTemplate restTemplate = new RestTemplate();

    public boolean check() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity("http://127.0.0.1:8000/api", String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}
