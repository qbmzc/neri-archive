package moe.ouom.archive;

import java.nio.file.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ArchiveApplication {
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(System.getenv().getOrDefault("ARCHIVE_DATA", "./data")));
        SpringApplication.run(ArchiveApplication.class, args);
    }
}
