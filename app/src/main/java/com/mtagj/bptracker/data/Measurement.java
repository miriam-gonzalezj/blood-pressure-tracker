package com.mtagj.bptracker.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One blood pressure reading. Field names mirror the author's existing
 * spreadsheet columns (fecha, hora, systólica, diastólica, pulso, brazo, altura, obs) so
 * the CSV export lines up with her historical data.
 *
 * "días transcurridos" (days since previous reading) and "var sistólica" /
 * "var diastólica" (change vs. previous reading) are NOT stored here on
 * purpose — they depend on whichever reading came immediately before this
 * one, which can change if you edit or delete an older entry. They are
 * computed on the fly (see MeasurementRepository / AnalyticsActivity /
 * CsvExporter) from readings ordered by timestamp, which keeps them always
 * correct instead of silently going stale.
 */
@Entity(tableName = "measurements")
public class Measurement {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Date + time of the reading, as epoch milliseconds (UTC), for easy sorting/filtering. */
    @ColumnInfo(name = "timestamp_millis")
    public long timestampMillis;

    @ColumnInfo(name = "systolic")
    public int systolic;

    @ColumnInfo(name = "diastolic")
    public int diastolic;

    @ColumnInfo(name = "pulse")
    public int pulse;

    /** "izquierdo" or "derecho" (left/right arm). */
    @ColumnInfo(name = "arm")
    public String arm;

    /** Free-text altitude, e.g. "1500 m snm" — auto-filled from GPS when available, always editable. */
    @ColumnInfo(name = "altitude")
    public String altitude;

    /** Optional free-text observation (matches the "obs" column, e.g. "S" for a symptom note). */
    @ColumnInfo(name = "note")
    public String note;

    public Measurement() {
    }

    @androidx.room.Ignore
    public Measurement(long timestampMillis, int systolic, int diastolic, int pulse,
                        String arm, String altitude, String note) {
        this.timestampMillis = timestampMillis;
        this.systolic = systolic;
        this.diastolic = diastolic;
        this.pulse = pulse;
        this.arm = arm;
        this.altitude = altitude;
        this.note = note;
    }
}
