package com.mtagj.bptracker.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface MeasurementDao {

    @Insert
    long insert(Measurement measurement);

    @Update
    void update(Measurement measurement);

    @Delete
    void delete(Measurement measurement);

    @Query("SELECT * FROM measurements ORDER BY timestamp_millis DESC")
    List<Measurement> getAllOrderedNewestFirst();

    @Query("SELECT * FROM measurements ORDER BY timestamp_millis ASC")
    List<Measurement> getAllOrderedOldestFirst();

    @Query("SELECT * FROM measurements WHERE timestamp_millis BETWEEN :startMillis AND :endMillis ORDER BY timestamp_millis ASC")
    List<Measurement> getBetween(long startMillis, long endMillis);

    @Query("SELECT * FROM measurements WHERE id = :id LIMIT 1")
    Measurement getById(long id);

    /** Null cuando la tabla está vacía (MIN/MAX de SQLite sobre cero filas da NULL, no 0). */
    @Query("SELECT MIN(timestamp_millis) FROM measurements")
    Long getMinTimestamp();

    @Query("SELECT MAX(timestamp_millis) FROM measurements")
    Long getMaxTimestamp();
}
