package no.healthlog.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDayDao {
    @Query("SELECT * FROM health_days ORDER BY date DESC")
    fun observeAll(): Flow<List<HealthDay>>

    @Query("SELECT * FROM health_days WHERE date = :date LIMIT 1")
    suspend fun get(date: String): HealthDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: HealthDay)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(days: List<HealthDay>)

    @Delete
    suspend fun delete(day: HealthDay)

    @Query("DELETE FROM health_days WHERE date = :date")
    suspend fun delete(date: String)
}
