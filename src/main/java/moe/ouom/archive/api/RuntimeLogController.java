package moe.ouom.archive.api;

import moe.ouom.archive.logging.RuntimeLogAppender;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class RuntimeLogController {
    @GetMapping public ResponseEntity<List<RuntimeLogAppender.Entry>> logs(
            @RequestParam(defaultValue="ALL") String level, @RequestParam(defaultValue="") String query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RuntimeLogAppender.recent(level, query));
    }
}
