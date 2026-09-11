package moe.ouom.archive.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迁移必须能在「已存在的旧库」上补列——{@code schema.sql} 的 CREATE TABLE IF NOT EXISTS
 * 对旧库不会生效，这正是引入 SchemaMigrations 的原因。
 */
class SchemaMigrationsTest {
    @TempDir Path root;

    static JdbcTemplate database(Path file) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+file));
    }

    @Test void addsMissingColumnsToAnExistingDatabase() {
        var db=database(root.resolve("old.db"));
        // 旧版 schema：tasks 没有 forced，file_inventory 没有 song_id。
        db.execute("CREATE TABLE tasks(id INTEGER PRIMARY KEY, song_id INTEGER, status TEXT NOT NULL DEFAULT 'QUEUED')");
        db.execute("CREATE TABLE file_inventory(path TEXT PRIMARY KEY, bytes INTEGER)");
        db.execute("INSERT INTO tasks(id,song_id) VALUES(1,11)");
        db.execute("INSERT INTO file_inventory(path,bytes) VALUES('a.flac',1)");

        new SchemaMigrations(db);

        assertTrue(columns(db,"tasks").contains("forced"));
        assertTrue(columns(db,"file_inventory").contains("song_id"));
        assertTrue(indexes(db,"file_inventory").contains("idx_inventory_song"));
        // 已有行取默认值，不会被回填成错误数据。
        assertEquals(0,db.queryForObject("SELECT forced FROM tasks WHERE id=1",Integer.class));
        assertNull(db.queryForObject("SELECT song_id FROM file_inventory WHERE path='a.flac'",Object.class));
    }

    @Test void repeatedRunsAreIdempotent() {
        var db=database(root.resolve("again.db"));
        db.execute("CREATE TABLE tasks(id INTEGER PRIMARY KEY)");
        db.execute("CREATE TABLE file_inventory(path TEXT PRIMARY KEY)");

        new SchemaMigrations(db);
        new SchemaMigrations(db);

        assertEquals(1,columns(db,"tasks").stream().filter("forced"::equals).count());
        assertTrue(columns(db,"file_inventory").contains("song_id"));
    }

    @Test void missingTableFailsLoudlyInsteadOfSkippingMigration() {
        var error=assertThrows(IllegalStateException.class,()->new SchemaMigrations(database(root.resolve("empty.db"))));
        assertTrue(error.getMessage().contains("tasks"),error.getMessage());
    }

    /**
     * 生产事故回归：已发布的 0.1.0 数据库在启动时直接崩溃。
     *
     * <p>{@code schema.sql} 由 Spring 在 {@code SchemaMigrations} 之前执行，而它对已存在的表是
     * {@code CREATE TABLE IF NOT EXISTS} 的 no-op。因此 schema.sql 里任何引用「由迁移新增的列」的
     * 语句（索引、视图）都会在旧库上失败，整个应用起不来。
     *
     * <p>本测试按真实启动顺序执行两份脚本，旧库必须能升级成功。
     */
    @Test void upgradesAReleasedDatabaseWithoutFailingOnSchemaSql() throws Exception {
        var source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("released.db"));
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("legacy-schema-0.1.0.sql"));
            // 这一步曾经抛 [SQLITE_ERROR] no such column: song_id
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("schema.sql"));
        }

        new SchemaMigrations(new JdbcTemplate(source));

        var db=new JdbcTemplate(source);
        assertTrue(columns(db,"tasks").contains("forced"));
        assertTrue(columns(db,"file_inventory").contains("song_id"));
        assertTrue(indexes(db,"file_inventory").contains("idx_inventory_song"));
    }

    static List<String> columns(JdbcTemplate db,String table) {
        return db.queryForList("PRAGMA table_info("+table+")").stream().map(row->String.valueOf(row.get("name"))).toList();
    }

    static List<String> indexes(JdbcTemplate db,String table) {
        return db.queryForList("PRAGMA index_list("+table+")").stream().map(row->String.valueOf(row.get("name"))).toList();
    }
}
