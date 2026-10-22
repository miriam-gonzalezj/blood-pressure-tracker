package com.mtagj.bptracker.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.mtagj.bptracker.R;
import com.mtagj.bptracker.data.Measurement;
import com.mtagj.bptracker.data.MeasurementRepository;
import com.mtagj.bptracker.data.MeasurementWithDeltas;
import com.mtagj.bptracker.databinding.ActivityHistoryBinding;
import com.mtagj.bptracker.databinding.DialogEditMeasurementBinding;
import com.mtagj.bptracker.util.CsvExporter;
import com.mtagj.bptracker.util.DateTimeUtils;
import com.mtagj.bptracker.util.PdfTableExporter;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;

public class HistoryActivity extends AppCompatActivity implements MeasurementAdapter.Listener {

    private ActivityHistoryBinding binding;
    private MeasurementRepository repository;
    private MeasurementAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        repository = new MeasurementRepository(this);
        adapter = new MeasurementAdapter(this);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        binding.btnExportCsv.setOnClickListener(v -> exportAll());
        binding.btnSharePdf.setOnClickListener(v -> sharePdfTable());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Vuelve a leer de la base de datos cada vez que esta pantalla se muestra, no solo
        // la primera vez que se crea. Si no, cuando se llega aquí reutilizando una instancia
        // ya existente (por ejemplo, Analytics -> Historial usa FLAG_ACTIVITY_REORDER_TO_FRONT,
        // que trae al frente la pantalla existente SIN volver a llamar a onCreate), la lista
        // se queda con los datos de cuando se abrió por primera vez. Eso es justo lo que
        // Reportado en uso real: se agregó una medición nueva, se volvió a Historial y todavía mostraba
        // solo la anterior, mientras que "Compartir PDF" sí mostraba las dos porque ese
        // botón consulta la base de datos en el momento en que se presiona, no al crear
        // la pantalla.
        loadData();
    }

    private void loadData() {
        repository.getAllWithDeltas(list -> {
            adapter.submitList(list);
            binding.tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_capture) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return true;
        } else if (id == R.id.action_analytics) {
            startActivity(new Intent(this, AnalyticsActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return true;
        } else if (id == R.id.action_history) {
            return true; // already here
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onEdit(MeasurementWithDeltas item) {
        showEditDialog(item.measurement);
    }

    @Override
    public void onDelete(MeasurementWithDeltas item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) ->
                        repository.delete(item.measurement, this::loadData))
                .show();
    }

    private void showEditDialog(Measurement measurement) {
        DialogEditMeasurementBinding dialogBinding = DialogEditMeasurementBinding.inflate(getLayoutInflater());

        dialogBinding.etSystolic.setText(String.valueOf(measurement.systolic));
        dialogBinding.etDiastolic.setText(String.valueOf(measurement.diastolic));
        dialogBinding.etPulse.setText(String.valueOf(measurement.pulse));
        dialogBinding.etAltitude.setText(measurement.altitude);
        dialogBinding.etNote.setText(measurement.note);
        dialogBinding.spinnerArm.setSelection("derecho".equals(measurement.arm) ? 1 : 0);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(measurement.timestampMillis);
        refreshDialogDateTimeLabels(dialogBinding, cal);

        dialogBinding.btnPickDate.setOnClickListener(v -> new DatePickerDialog(this, (view, year, month, day) -> {
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, day);
            refreshDialogDateTimeLabels(dialogBinding, cal);
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show());

        dialogBinding.btnPickTime.setOnClickListener(v -> new TimePickerDialog(this, (view, hour, minute) -> {
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            refreshDialogDateTimeLabels(dialogBinding, cal);
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show());

        new AlertDialog.Builder(this)
                .setTitle(R.string.edit)
                .setView(dialogBinding.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    Integer sys = parseIntOrNull(dialogBinding.etSystolic.getText().toString());
                    Integer dia = parseIntOrNull(dialogBinding.etDiastolic.getText().toString());
                    Integer pulse = parseIntOrNull(dialogBinding.etPulse.getText().toString());
                    if (sys == null || dia == null || pulse == null) {
                        Toast.makeText(this, R.string.fix_fields_before_saving, Toast.LENGTH_LONG).show();
                        return;
                    }
                    measurement.systolic = sys;
                    measurement.diastolic = dia;
                    measurement.pulse = pulse;
                    measurement.timestampMillis = cal.getTimeInMillis();
                    measurement.arm = (String) dialogBinding.spinnerArm.getSelectedItem();
                    String altitude = dialogBinding.etAltitude.getText().toString().trim();
                    String note = dialogBinding.etNote.getText().toString().trim();
                    measurement.altitude = altitude.isEmpty() ? null : altitude;
                    measurement.note = note.isEmpty() ? null : note;

                    repository.update(measurement, this::loadData);
                })
                .show();
    }

    private void refreshDialogDateTimeLabels(DialogEditMeasurementBinding dialogBinding, Calendar cal) {
        dialogBinding.btnPickDate.setText(DateTimeUtils.formatDate(cal.getTimeInMillis()));
        dialogBinding.btnPickTime.setText(DateTimeUtils.formatTime(cal.getTimeInMillis()));
    }

    private void exportAll() {
        repository.getRangeWithDeltas(Long.MIN_VALUE, Long.MAX_VALUE, (List<MeasurementWithDeltas> rows) -> {
            if (rows.isEmpty()) {
                Toast.makeText(this, R.string.no_data_in_range, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                File csv = CsvExporter.writeCsv(this, rows);
                startActivity(CsvExporter.shareIntent(this, csv));
            } catch (IOException e) {
                Toast.makeText(this, "No se pudo exportar: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Full record-by-record table as a compact, small-font PDF -- for the doctor visit, alongside the summary. */
    private void sharePdfTable() {
        repository.getRangeWithDeltas(Long.MIN_VALUE, Long.MAX_VALUE, (List<MeasurementWithDeltas> rows) -> {
            if (rows.isEmpty()) {
                Toast.makeText(this, R.string.no_data_in_range, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                File pdf = PdfTableExporter.exportAndSave(this, rows);
                startActivity(PdfTableExporter.shareIntent(this, pdf));
            } catch (IOException e) {
                Toast.makeText(this, "No se pudo generar el PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static Integer parseIntOrNull(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
