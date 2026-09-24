package com.example.baseballmotionanalyzer.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.baseballmotionanalyzer.model.PlayerProfile
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SwingRecordEntity(
    val id: Long = 0,
    val jerseyNumber: String,
    val playerName: String,
    val heightCm: Float,
    val stance: String, // "右打 (RHH)" 或 "左打 (LHH)"
    val speedKmh: Float,
    val launchAngleDeg: Float,
    val distanceMeters: Float,
    val angularVelocityDegSec: Float,
    val timestamp: String
)

class SwingDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "baseball_motion.db"
        private const val DATABASE_VERSION = 1

        // 表名
        private const val TABLE_PLAYERS = "players"
        private const val TABLE_SWINGS = "swing_records"

        // players 欄位
        private const val KEY_PLAYER_ID = "id"
        private const val KEY_PLAYER_JERSEY = "jersey_number"
        private const val KEY_PLAYER_NAME = "name"
        private const val KEY_PLAYER_HEIGHT = "height_cm"
        private const val KEY_PLAYER_IS_RIGHT = "is_right_handed"

        // swing_records 欄位
        private const val KEY_SWING_ID = "id"
        private const val KEY_SWING_JERSEY = "jersey_number"
        private const val KEY_SWING_PLAYER_NAME = "player_name"
        private const val KEY_SWING_HEIGHT = "height_cm"
        private const val KEY_SWING_STANCE = "stance"
        private const val KEY_SWING_SPEED = "speed_kmh"
        private const val KEY_SWING_ANGLE = "launch_angle_deg"
        private const val KEY_SWING_DISTANCE = "distance_meters"
        private const val KEY_SWING_ANGULAR_VEL = "angular_velocity_deg_sec"
        private const val KEY_SWING_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createPlayersTable = """
            CREATE TABLE $TABLE_PLAYERS (
                $KEY_PLAYER_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $KEY_PLAYER_JERSEY TEXT,
                $KEY_PLAYER_NAME TEXT,
                $KEY_PLAYER_HEIGHT REAL,
                $KEY_PLAYER_IS_RIGHT INTEGER
            )
        """.trimIndent()

        val createSwingsTable = """
            CREATE TABLE $TABLE_SWINGS (
                $KEY_SWING_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $KEY_SWING_JERSEY TEXT,
                $KEY_SWING_PLAYER_NAME TEXT,
                $KEY_SWING_HEIGHT REAL,
                $KEY_SWING_STANCE TEXT,
                $KEY_SWING_SPEED REAL,
                $KEY_SWING_ANGLE REAL,
                $KEY_SWING_DISTANCE REAL,
                $KEY_SWING_ANGULAR_VEL REAL,
                $KEY_SWING_TIMESTAMP TEXT
            )
        """.trimIndent()

        db.execSQL(createPlayersTable)
        db.execSQL(createSwingsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PLAYERS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SWINGS")
        onCreate(db)
    }

    // --- 打者 Profile 操作 ---
    fun saveOrUpdatePlayer(player: PlayerProfile): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(KEY_PLAYER_JERSEY, player.jerseyNumber)
            put(KEY_PLAYER_NAME, player.name)
            put(KEY_PLAYER_HEIGHT, player.heightCm)
            put(KEY_PLAYER_IS_RIGHT, if (player.isRightHanded) 1 else 0)
        }

        val cursor = db.query(
            TABLE_PLAYERS,
            arrayOf(KEY_PLAYER_ID),
            "$KEY_PLAYER_JERSEY = ? AND $KEY_PLAYER_NAME = ?",
            arrayOf(player.jerseyNumber, player.name),
            null, null, null
        )

        return if (cursor.moveToFirst()) {
            val existingId = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_PLAYER_ID))
            cursor.close()
            db.update(TABLE_PLAYERS, cv, "$KEY_PLAYER_ID = ?", arrayOf(existingId.toString()))
            existingId
        } else {
            cursor.close()
            db.insert(TABLE_PLAYERS, null, cv)
        }
    }

    fun getAllPlayers(): List<PlayerProfile> {
        val list = mutableListOf<PlayerProfile>()
        val db = readableDatabase
        val cursor = db.query(TABLE_PLAYERS, null, null, null, null, null, "$KEY_PLAYER_ID DESC")

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_PLAYER_ID))
                val jersey = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PLAYER_JERSEY))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PLAYER_NAME))
                val height = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_PLAYER_HEIGHT))
                val isRight = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_PLAYER_IS_RIGHT)) == 1

                list.add(
                    PlayerProfile(
                        id = id,
                        jerseyNumber = jersey,
                        name = name,
                        heightCm = height,
                        isRightHanded = isRight
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    // --- 揮棒紀錄 CRUD 操作 ---
    fun insertSwingRecord(record: SwingRecordEntity): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(KEY_SWING_JERSEY, record.jerseyNumber)
            put(KEY_SWING_PLAYER_NAME, record.playerName)
            put(KEY_SWING_HEIGHT, record.heightCm)
            put(KEY_SWING_STANCE, record.stance)
            put(KEY_SWING_SPEED, record.speedKmh)
            put(KEY_SWING_ANGLE, record.launchAngleDeg)
            put(KEY_SWING_DISTANCE, record.distanceMeters)
            put(KEY_SWING_ANGULAR_VEL, record.angularVelocityDegSec)
            put(KEY_SWING_TIMESTAMP, record.timestamp)
        }
        return db.insert(TABLE_SWINGS, null, cv)
    }

    fun getAllSwingRecords(): List<SwingRecordEntity> {
        val list = mutableListOf<SwingRecordEntity>()
        val db = readableDatabase
        val cursor = db.query(TABLE_SWINGS, null, null, null, null, null, "$KEY_SWING_ID DESC")

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_SWING_ID))
                val jersey = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SWING_JERSEY))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SWING_PLAYER_NAME))
                val height = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_SWING_HEIGHT))
                val stance = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SWING_STANCE))
                val speed = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_SWING_SPEED))
                val angle = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_SWING_ANGLE))
                val dist = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_SWING_DISTANCE))
                val angVel = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_SWING_ANGULAR_VEL))
                val time = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SWING_TIMESTAMP))

                list.add(
                    SwingRecordEntity(
                        id = id,
                        jerseyNumber = jersey,
                        playerName = name,
                        heightCm = height,
                        stance = stance,
                        speedKmh = speed,
                        launchAngleDeg = angle,
                        distanceMeters = dist,
                        angularVelocityDegSec = angVel,
                        timestamp = time
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun deleteSwingRecord(id: Long): Boolean {
        val db = writableDatabase
        val rows = db.delete(TABLE_SWINGS, "$KEY_SWING_ID = ?", arrayOf(id.toString()))
        return rows > 0
    }

    fun deleteAllSwingRecords(): Boolean {
        val db = writableDatabase
        val rows = db.delete(TABLE_SWINGS, null, null)
        return rows >= 0
    }

    // --- CSV 匯出 ---
    fun exportToCsvFile(context: Context): File? {
        val records = getAllSwingRecords()
        if (records.isEmpty()) return null

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Baseball_Swings_$timeStamp.csv"
        val exportDir = File(context.getExternalFilesDir(null), "exports")
        if (!exportDir.exists()) exportDir.mkdirs()

        val csvFile = File(exportDir, fileName)
        FileWriter(csvFile).use { writer ->
            writer.append("ID,打者背號,打者姓名,打者身高(cm),打擊習慣,擊球初速(km/h),出球仰角(度),估算距離(m),軀幹角速度(deg/s),時間戳記\n")
            for (r in records) {
                writer.append("${r.id},\"${r.jerseyNumber}\",\"${r.playerName}\",${r.heightCm.toInt()},\"${r.stance}\",${String.format(Locale.US, "%.1f", r.speedKmh)},${String.format(Locale.US, "%.1f", r.launchAngleDeg)},${String.format(Locale.US, "%.1f", r.distanceMeters)},${r.angularVelocityDegSec.toInt()},\"${r.timestamp}\"\n")
            }
        }
        return csvFile
    }
}
