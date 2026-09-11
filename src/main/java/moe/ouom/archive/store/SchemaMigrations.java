package moe.ouom.archive.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 幂等的增量 schema 迁移。
 *
 * <p>{@code schema.sql} 全部使用 {@code CREATE TABLE IF NOT EXISTS}，对已存在的数据库不会补列，
 * 而项目没有引入 Flyway/Liquibase。启动时在这里补齐新增列。
 *
 * <p>执行时机：本类依赖 {@link JdbcTemplate}（间接依赖 DataSource），Spring Boot 会为依赖
 * DataSource 的 bean 建立「初始化脚本先执行」的 depends-on 关系，因此 {@code schema.sql}
 * 必然已经执行完毕。若该前提被破坏，下面的缺表检查会直接抛错，而不是静默跳过迁移。
 */
@Component
public class SchemaMigrations {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);
    private final JdbcTemplate db;

    public SchemaMigrations(JdbcTemplate db) {
        this.db = db;
        migrate();
    }

    void migrate() {
        addColumn("tasks", "forced", "INTEGER NOT NULL DEFAULT 0");
        addColumn("file_inventory", "song_id", "INTEGER");
        db.execute("CREATE INDEX IF NOT EXISTS idx_inventory_song ON file_inventory(song_id)");
    }

    private void addColumn(String table, String column, String definition) {
        List<String> existing = columns(table);
        if (existing.isEmpty()) throw new IllegalStateException("数据表不存在，schema.sql 未执行：" + table);
        if (existing.contains(column)) return;
        db.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("数据库迁移：已添加 {}.{}", table, column);
    }

    private List<String> columns(String table) {
        return db.queryForList("PRAGMA table_info(" + table + ")").stream()
                .map(row -> Objects.toString(row.get("name"), ""))
                .filter(name -> !name.isBlank())
                .toList();
    }
}
