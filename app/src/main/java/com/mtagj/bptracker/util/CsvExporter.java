package com.mtagj.bptracker.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.mtagj.bptracker.data.MeasurementWithDeltas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exports a list of readings to a CSV file with the same columns as the
 * author's own spreadsheet (tracking_v2 sheet): fecha, hora, systólica,
 * diastólica, pulso, brazo, altura, obs, días transcurridos, var sistólica,
 * var diastólica — so it can be opened directly in Excel/Sheets or appended
 * to an existing tracking file.
 */
public final class CsvExporter {

    private CsvExporter() {
    }

    public static File writeCsv(Context context, List<MeasurementWithDeltas> oldestFirst) throws IOException {
        File dir = new File(context.getCacheDir(), "exports");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "presion_arterial.csv");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write("fecha,hora,sistólica,diastólica,pulso,brazo,altura,obs,días transcurridos,var sistólica,var diastólica\n");
            for (MeasurementWithDeltas row : oldestFirst) {
                writer.write(toCsvLine(row));
                writer.write("\n");
            }
        }
        return file;
    }

    private static String toCsvLine(MeasurementWithDeltas row) {
        long ts = row.measurement.timestampMillis;
        return String.join(",",
                DateTimeUtils.formatDate(ts),
                DateTimeUtils.formatTime(ts),
                String.valueOf(row.measurement.systolic),
                String.valueOf(row.measurement.diastolic),
                String.valueOf(row.measurement.pulse),
                escape(row.measurement.arm),
                escape(row.measurement.altitude),
                escape(row.measurement.note),
                row.daysElapsed == null ? "" : String.valueOf(row.daysElapsed),
                row.varSystolic == null ? "" : String.valueOf(row.varSystolic),
                row.varDiastolic == null ? "" : String.valueOf(row.varDiastolic)
        );
    }

    private static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Builds a share Intent (chooser) for the already-written CSV file. */
    public static Intent shareIntent(Context context, File csvFile) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", csvFile);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(intent, "Exportar mediciones");
    }
}
