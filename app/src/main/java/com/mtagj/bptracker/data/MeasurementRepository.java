package com.mtagj.bptracker.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the DAO that (a) moves all database work off the main
 * thread, the way Room requires, and (b) computes the derived
 * days-elapsed/variation columns from her spreadsheet. Kept deliberately
 * simple (a single background executor + main-thread callback) rather than
 * a full LiveData/ViewModel setup, since this app has exactly one user and
 * a handful of screens.
 */
public class MeasurementRepository {

    public interface Callback<T> {
        void onResult(T result);
    }

    private final MeasurementDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public MeasurementRepository(Context context) {
        this.dao = AppDatabase.getInstance(context).measurementDao();
    }

    public void insert(Measurement measurement, Callback<Long> onDone) {
        executor.execute(() -> {
            long id = dao.insert(measurement);
            if (onDone != null) mainHandler.post(() -> onDone.onResult(id));
        });
    }

    public void update(Measurement measurement, Runnable onDone) {
        executor.execute(() -> {
            dao.update(measurement);
            if (onDone != null) mainHandler.post(onDone);
        });
    }

    public void delete(Measurement measurement, Runnable onDone) {
        executor.execute(() -> {
            dao.delete(measurement);
            if (onDone != null) mainHandler.post(onDone);
        });
    }

    public void getById(long id, Callback<Measurement> onDone) {
        executor.execute(() -> {
            Measurement m = dao.getById(id);
            mainHandler.post(() -> onDone.onResult(m));
        });
    }

    /**
     * Fecha de la primera y la última medición guardada, como {@code [minMillis, maxMillis]} --
     * o {@code null} si todavía no hay ninguna medición. Se usa para que el resumen tome por
     * defecto "toda la historia" (de la primera a la última lectura real) en vez de una
     * ventana de calendario arbitraria como "últimos 30 días", que podría no coincidir con
     * ninguna lectura real si hace tiempo que no se toma la presión o si apenas empezó a usar
     * la app.
     */
    public void getDateBounds(Callback<long[]> onDone) {
        executor.execute(() -> {
            Long min = dao.getMinTimestamp();
            Long max = dao.getMaxTimestamp();
            long[] bounds = (min == null || max == null) ? null : new long[]{min, max};
            mainHandler.post(() -> onDone.onResult(bounds));
        });
    }

    /** Newest-first list with derived columns, ready for the History screen. */
    public void getAllWithDeltas(Callback<List<MeasurementWithDeltas>> onDone) {
        executor.execute(() -> {
            List<Measurement> oldestFirst = dao.getAllOrderedOldestFirst();
            List<MeasurementWithDeltas> withDeltas = withDeltas(oldestFirst);
            List<MeasurementWithDeltas> newestFirst = new ArrayList<>(withDeltas);
            java.util.Collections.reverse(newestFirst);
            mainHandler.post(() -> onDone.onResult(newestFirst));
        });
    }

    /** Oldest-first list with derived columns, restricted to [startMillis, endMillis], for Analytics/CSV export. */
    public void getRangeWithDeltas(long startMillis, long endMillis, Callback<List<MeasurementWithDeltas>> onDone) {
        executor.execute(() -> {
            List<Measurement> inRange = dao.getBetween(startMillis, endMillis);
            List<MeasurementWithDeltas> withDeltas = withDeltas(inRange);
            mainHandler.post(() -> onDone.onResult(withDeltas));
        });
    }

    /** Input must already be sorted oldest-first by timestamp. */
    private static List<MeasurementWithDeltas> withDeltas(List<Measurement> oldestFirst) {
        List<MeasurementWithDeltas> result = new ArrayList<>(oldestFirst.size());
        Measurement previous = null;
        for (Measurement m : oldestFirst) {
            Integer daysElapsed = null, varSys = null, varDia = null;
            if (previous != null) {
                long diffMillis = m.timestampMillis - previous.timestampMillis;
                daysElapsed = (int) TimeUnit.MILLISECONDS.toDays(diffMillis);
                varSys = m.systolic - previous.systolic;
                varDia = m.diastolic - previous.diastolic;
            }
            result.add(new MeasurementWithDeltas(m, daysElapsed, varSys, varDia));
            previous = m;
        }
        return result;
    }
}
