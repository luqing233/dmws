package fun.luqing.dmws.repository.dmw;

import fun.luqing.dmws.entity.dmw.AiContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiContextRepository extends JpaRepository<AiContext, Long> {

}