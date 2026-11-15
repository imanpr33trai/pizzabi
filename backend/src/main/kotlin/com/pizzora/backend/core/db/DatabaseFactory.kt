package com.pizzora.backend.core.db

import com.pizzora.backend.core.config.DatabaseConfig
import com.pizzora.backend.core.health.DependencyProbe
import com.pizzora.contracts.DependencyHealthStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory

class DatabaseHandle(
  val dataSource: DataSource,
  val database: Database,
) : AutoCloseable {
  override fun close() {
    if (dataSource is AutoCloseable) {
      dataSource.close()
    }
  }
}

object DatabaseFactory {
  private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

  fun createDataSource(config: DatabaseConfig): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
      jdbcUrl = config.jdbcUrl
      username = config.username
      password = config.password
      driverClassName = "org.postgresql.Driver"
      maximumPoolSize = config.maxPoolSize
      minimumIdle = 2
      isAutoCommit = false
      transactionIsolation = "TRANSACTION_READ_COMMITTED"
      connectionTimeout = 5_000L
      validationTimeout = 3_000L
      poolName = "pizzora-hikari"
    }
    return HikariDataSource(hikariConfig)
  }

  fun migrate(dataSource: DataSource): Int {
    logger.info("Running Flyway migrations against PostgreSQL")
    val result = Flyway.configure()
      .dataSource(dataSource)
      .locations("classpath:db/migration")
      .validateMigrationNaming(true)
      .load()
      .migrate()
    logger.info("Flyway migration complete migrationsExecuted={}", result.migrationsExecuted)
    return result.migrationsExecuted
  }

  fun connect(dataSource: DataSource, verifyMigrated: Boolean = false): DatabaseHandle {
    if (verifyMigrated) {
      val info = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .info()
      val pending = info.pending()
      if (pending.isNotEmpty()) {
        val versions = pending.joinToString(",") { it.version?.version ?: it.description }
        throw IllegalStateException(
          "Pending Flyway migrations detected ($versions). Run pre-deploy migration (--migrate-only) before starting API or worker.",
        )
      }
      logger.info("Verified Flyway schema is up-to-date currentVersion={}", info.current()?.version?.version ?: "none")
    }
    val exposedDb = Database.connect(dataSource)
    return DatabaseHandle(dataSource = dataSource, database = exposedDb)
  }

  fun migrateAndConnect(dataSource: DataSource): DatabaseHandle {
    migrate(dataSource)
    return connect(dataSource, verifyMigrated = false)
  }

  suspend fun <T> dbQuery(
    database: Database,
    block: suspend Transaction.() -> T,
  ): T = newSuspendedTransaction(context = Dispatchers.IO, db = database) {
    block()
  }

  suspend fun <T> withJdbcTransaction(
    dataSource: DataSource,
    isolationLevel: Int = Connection.TRANSACTION_READ_COMMITTED,
    block: (Connection) -> T,
  ): T = withContext(Dispatchers.IO) {
    dataSource.connection.use { conn ->
      val previousAutoCommit = conn.autoCommit
      val previousIsolation = conn.transactionIsolation
      conn.autoCommit = false
      conn.transactionIsolation = isolationLevel
      try {
        val result = block(conn)
        conn.commit()
        result
      } catch (t: Throwable) {
        runCatching { conn.rollback() }
        throw t
      } finally {
        runCatching {
          conn.transactionIsolation = previousIsolation
          conn.autoCommit = previousAutoCommit
        }
      }
    }
  }
}

class PostgresJdbcProbe(
  private val dataSource: DataSource,
  override val name: String = "postgres",
  override val required: Boolean = true,
) : DependencyProbe {
  override suspend fun check(): DependencyHealthStatus = withContext(Dispatchers.IO) {
    runCatching {
      dataSource.connection.use { conn ->
        conn.createStatement().use { stmt ->
          stmt.queryTimeout = 2
          stmt.executeQuery("SELECT 1").use { rs ->
            if (rs.next()) DependencyHealthStatus.UP else DependencyHealthStatus.DOWN
          }
        }
      }
    }.getOrElse { DependencyHealthStatus.DOWN }
  }
}
