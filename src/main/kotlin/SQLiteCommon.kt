package org.tera201.sql

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.sql.*
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

abstract class SQLiteCommon(url:String) {
    val conn: Connection
    abstract val tableCreationQueries: Map<String, String>
    private val log: Logger = LoggerFactory.getLogger(SQLiteCommon::class.java)

    init {
        try {
            Class.forName("org.sqlite.JDBC") // Explicitly load SQLite driver
        } catch (e: ClassNotFoundException) {
            throw RuntimeException("SQLite JDBC driver not found!", e)
        }
        conn = createDatabaseConnection("jdbc:sqlite:$url")
    }

    protected fun createDatabaseConnection(dbUrl: String): Connection {
        return try {
            DriverManager.getConnection(dbUrl).also { enableForeignKeys(it) }.also { createTables() }
        } catch (e: SQLException) {
            throw RuntimeException("Error connecting to the database: ${e.message}", e)
        }
    }

    fun closeConnection() {
        conn.close()
    }

    /** Creates necessary tables in the SQLite database if they do not already exist. */
    fun createTables() {
        try {
            conn.createStatement().use { stmt ->
                for ((tableName, sqlQuery) in tableCreationQueries) {
                    stmt.execute(sqlQuery)
                    log.info("Table $tableName created successfully.")
                }
            }
        } catch (e: SQLException) {
            log.error("Error creating tables: ${e.message}")
        }
    }

    /** Drops tables from the SQLite database if they exist. */
    fun dropTables() {
        try {
            conn.createStatement().use { stmt ->
                for (table in tableCreationQueries.keys) {
                    stmt.execute("DROP TABLE IF EXISTS $table")
                    log.info("Dropped table: $table")
                }
            }
        } catch (e: SQLException) {
            log.error("Error dropping tables: ${e.message}")
        }
    }

    protected fun enableForeignKeys(connection: Connection) {
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON;") }
    }

    /** Helper function to set parameters for PreparedStatements */
    protected fun setParams(pstmt: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { index, param ->
            when (param) {
                is Int -> pstmt.setInt(index + 1, param)
                is Int? -> pstmt.setIntOrNull(index + 1, param)
                is String -> pstmt.setString(index + 1, param)
                is Long -> pstmt.setLong(index + 1, param)
                else -> pstmt.setObject(index + 1, param)
            }
        }
    }

    /** Generic function to execute a SELECT query */
    protected fun <T> executeQuery(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T {
        return conn.prepareStatement(sql).use { pstmt ->
            setParams(pstmt, params)
            pstmt.executeQuery().use { rs -> mapper(rs) }
        }
    }

    /** Generic function to execute an UPDATE or INSERT query */
    protected fun executeUpdate(sql: String, vararg params: Any?): Boolean {
        return conn.prepareStatement(sql).use { pstmt ->
            setParams(pstmt, params)
            pstmt.executeUpdate() > 0
        }
    }

    /** Helper function to fetch last inserted ID */
    protected fun getLastInsertId(): Int {
        val sql = "SELECT last_insert_rowid()"
        return executeQuery(sql) { rs -> if (rs.next()) rs.getInt(1) else -1 }
    }

    /** Executes a query that checks if a record exists */
    protected fun isExistExecute(pstmt: PreparedStatement): Boolean {
        return pstmt.executeQuery().use { rs -> rs.next() }
    }

    /** Extension function to handle nullable Int values */
    protected fun PreparedStatement.setIntOrNull(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    protected fun getLastInsertStringId(): String {
        val sqlLastId = "SELECT last_insert_rowid()"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sqlLastId).use { rs ->
                if (rs.next()) {
                    return rs.getString(1)
                }
            }
        }
        return ""
    }

    protected fun <T> retryTransaction(action: () -> T, retries: Int = 10): T {
        repeat(retries) { attempt ->
            try {
                return action()
            } catch (e: SQLException) {
                if (e.message?.contains("SQLITE_BUSY") == true) {
                    println("Database is busy, retrying... (attempt ${attempt + 1})")
                    Thread.sleep(100)
                } else {
                    throw e
                }
            }
        }
        throw SQLException("Failed after $retries attempts due to database being busy.")
    }

    protected fun compress(input: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(input.toByteArray(Charsets.UTF_8))
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    protected fun decompress(compressed: String): String {
        val bytes = Base64.getDecoder().decode(compressed)
        ByteArrayInputStream(bytes).use { bis ->
            GZIPInputStream(bis).use { gzip ->
                return gzip.bufferedReader(Charsets.UTF_8).readText()
            }
        }
    }

    protected inline fun <reified T : Number> getIdResult(rs: ResultSet): T? {
        return if (rs.next()) {
            when (T::class) {
                Int::class -> rs.getInt("id") as T
                Long::class -> rs.getLong("id") as T
                else -> throw IllegalArgumentException("Unsupported type: ${T::class}")
            }
        } else {
            null
        }
    }
}