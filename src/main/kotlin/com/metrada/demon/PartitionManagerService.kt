package com.metrada.demon

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

@Service
//@EnableScheduling
class PartitionManagerService(
    private val dataSource: DataSource
) {
    private val jdbcTemplate = JdbcTemplate(dataSource)
    private val partitionIntervalDays = 1   // Новая партиция каждые 7 дней
    private val retentionDays = 30          // Храним данные 30 дней

    /**
     * Запускается каждый день в 3:00 ночи
     */
    @Scheduled(cron = "0 0 3 * * *")
    fun managePartitions() {
        println("Partition management started at ${LocalDate.now()}")
        createFuturePartitions()
        dropOldPartitions()
    }

    /**
     * Создаёт новые партиции на 2 месяца вперёд
     */
    fun createFuturePartitions() {
        val today = LocalDate.now()
        // Создаём партиции на 60 дней вперёд
        for (i in 0..60 step partitionIntervalDays) {
            val partitionDate = today.plusDays(i.toLong())
            val partitionName = "metrics_${partitionDate.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            val startDate = partitionDate
            val endDate = partitionDate.plusDays(partitionIntervalDays.toLong())
            
            createPartitionIfNotExists(partitionName, startDate, endDate)
        }
    }

    /**
     * Удаляет партиции старше retentionDays
     */
    fun dropOldPartitions() {
        val cutoffDate = LocalDate.now().minusDays(retentionDays.toLong())
        
        // Находим все партиции таблицы metrics
        val partitions = jdbcTemplate.queryForList(
            """
            SELECT tablename 
            FROM pg_tables 
            WHERE schemaname = 'public' 
              AND tablename LIKE 'metrics_%'
            """.trimIndent()
        ).map { it["tablename"] as String }

        for (partition in partitions) {
            // Извлекаем дату из имени партиции (metrics_YYYYMMDD)
            val dateStr = partition.removePrefix("metrics_")
            val partitionDate = LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE)
            
            if (partitionDate < cutoffDate) {
                dropPartition(partition)
            }
        }
    }

    private fun createPartitionIfNotExists(name: String, startDate: LocalDate, endDate: LocalDate) {
        // Проверяем, существует ли партиция
        val exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) > 0 FROM pg_tables WHERE tablename = ?",
            Boolean::class.java,
            name
        ) ?: false
        
        if (!exists) {
            val start = startDate.atStartOfDay()
            val end = endDate.atStartOfDay()
            
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS $name PARTITION OF metrics 
                FOR VALUES FROM ('$start') TO ('$end')
            """.trimIndent())
            
            println("Created partition: $name")
        }
    }

    private fun dropPartition(name: String) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS $name CASCADE")
        println("Dropped old partition: $name")
    }

    /**
     * Проверяет, партиционирована ли таблица
     */
    fun isPartitioned(): Boolean {
        val result = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM pg_class 
                WHERE relname = 'metrics' 
                AND relkind = 'p'
            )
            """.trimIndent(),
            Boolean::class.java
        ) ?: false
        return result
    }

    /**
     * Преобразует обычную таблицу в партиционированную (миграция)
     */
    fun migrateToPartitionedTable() {
        if (isPartitioned()) {
            println("Table already partitioned")
            return
        }

        println("Starting migration to partitioned table...")
        
        // 1. Создаём партиционированную таблицу
        jdbcTemplate.execute("""
            CREATE TABLE metrics_new (LIKE metrics INCLUDING ALL) PARTITION BY RANGE (timestamp)
        """.trimIndent())

        // 2. Создаём партиции для существующих данных
        val result = jdbcTemplate.queryForList(
            "SELECT MIN(timestamp) as min_ts, MAX(timestamp) as max_ts FROM metrics"
        ).first()
        
        val minDate = (result["min_ts"] as java.sql.Timestamp).toLocalDateTime().toLocalDate()
        val maxDate = (result["max_ts"] as java.sql.Timestamp).toLocalDateTime().toLocalDate()
        
        var current = minDate
        while (current <= maxDate) {
            val partitionName = "metrics_${current.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            val start = current.atStartOfDay()
            val end = current.plusDays(partitionIntervalDays.toLong()).atStartOfDay()
            jdbcTemplate.execute("""
                CREATE TABLE $partitionName PARTITION OF metrics_new 
                FOR VALUES FROM ('$start') TO ('$end')
            """.trimIndent())
            current = current.plusDays(partitionIntervalDays.toLong())
        }

        // 3. Переносим данные
        jdbcTemplate.execute("""
            INSERT INTO metrics_new SELECT * FROM metrics
        """.trimIndent())

        // 4. Переименовываем таблицы
        jdbcTemplate.execute("ALTER TABLE metrics RENAME TO metrics_old")
        jdbcTemplate.execute("ALTER TABLE metrics_new RENAME TO metrics")

        println("Migration completed")
    }
}