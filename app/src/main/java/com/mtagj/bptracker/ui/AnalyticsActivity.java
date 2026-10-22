package com.mtagj.bptracker.ui;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mtagj.bptracker.R;
import com.mtagj.bptracker.core.BPStatistics;
import com.mtagj.bptracker.core.StatsResult;
import com.mtagj.bptracker.data.MeasurementRepository;
import com.mtagj.bptracker.data.MeasurementWithDeltas;
import com.mtagj.bptracker.databinding.ActivityAnalyticsBinding;
import com.mtagj.bptracker.util.DateTimeUtils;
import com.mtagj.bptracker.util.PdfTableExporter;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Lets the user pick a date range and reproduces the block of stats already
 * computed by hand in a spreadsheet (n / AVG / MEDIAN / MAX / MIN / STD DEV
 * for systolic, diastolic and pulse), plus a trend chart. MODE was dropped at
 * her request ("no dice mucho en este reporte" -- true for BP readings,
 * which rarely repeat meaningfully) and pulse pressure (sistólica -
 * diastólica) was added instead -- plain arithmetic, not a classification,
 * shown as its own AVG/MEDIAN line under the stats table.
 */
public class AnalyticsActivity extends AppCompatActivity {

    private ActivityAnalyticsBinding binding;
    private MeasurementRepository repository;

    private final Calendar fromDate = Calendar.getInstance();
    private final Calendar toDate = Calendar.getInstance();
    private static final DecimalFormat DF1 = new DecimalFormat("0.0");
    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAnalyticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        repository = new MeasurementRepository(this);

        binding.btnFromDate.setOnClickListener(v -> pickDate(fromDate));
        binding.btnToDate.setOnClickListener(v -> pickDate(toDate));
        binding.btnCalculate.setOnClickListener(v -> calculate());
        binding.btnShareSummary.setOnClickListener(v -> shareSummary());
        binding.btnShareFullReport.setOnClickListener(v -> shareFullReport());

        // Rango por defecto: "toda la historia" -- de la fecha de la primera lectura guardada
        // a la de la más reciente -- en vez de una ventana fija como "últimos 30 días", que
        // podía no mostrar nada si hace tiempo que no se toma la presión. refreshDateButtons()
        // se llama de una vez con hoy/hoy para que los botones no se vean en blanco mientras
        // llega la respuesta de la base de datos; calculate() (disparado también por
        // onResume(), ver abajo) corrige el rango en cuanto la respuesta llega. Si ella luego
        // elige su propio periodo con los selectores de fecha, eso no se vuelve a pisar --
        // esto solo fija el punto de partida.
        refreshDateButtons();
        repository.getDateBounds(bounds -> {
            if (bounds != null) {
                fromDate.setTimeInMillis(bounds[0]);
                toDate.setTimeInMillis(bounds[1]);
                refreshDateButtons();
            }
            calculate();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Igual que en Historial: esta pantalla puede volver al frente reutilizando la
        // misma instancia (Historial -> Tendencias usa FLAG_ACTIVITY_REORDER_TO_FRONT,
        // que no vuelve a llamar a onCreate), así que hay que recalcular aquí y no solo
        // en onCreate -- si no, el resumen se queda con el n y los estadísticos de la
        // última vez que esta pantalla se creó, aunque se hayan agregado mediciones
        // nuevas desde entonces.
        calculate();
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
        } else if (id == R.id.action_history) {
            startActivity(new Intent(this, HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return true;
        } else if (id == R.id.action_analytics) {
            return true; // already here
        }
        return super.onOptionsItemSelected(item);
    }

    private void pickDate(Calendar target) {
        new DatePickerDialog(this, (view, year, month, day) -> {
            target.set(Calendar.YEAR, year);
            target.set(Calendar.MONTH, month);
            target.set(Calendar.DAY_OF_MONTH, day);
            refreshDateButtons();
        }, target.get(Calendar.YEAR), target.get(Calendar.MONTH), target.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void refreshDateButtons() {
        binding.btnFromDate.setText(getString(com.mtagj.bptracker.R.string.from_date) + ": "
                + DateTimeUtils.formatDate(fromDate.getTimeInMillis()));
        binding.btnToDate.setText(getString(com.mtagj.bptracker.R.string.to_date) + ": "
                + DateTimeUtils.formatDate(toDate.getTimeInMillis()));
    }

    /** Callback interno para engancharse a un cálculo recién terminado -- ver {@link #calculate(OnCalculated)}. */
    private interface OnCalculated {
        void run(List<MeasurementWithDeltas> rows);
    }

    private void calculate() {
        calculate(null);
    }

    /**
     * {@code after}, si no es null, corre justo después de que la pantalla ya se actualizó
     * con los datos frescos de este cálculo -- con la MISMA lista de filas que se usó para
     * pintarla, para que quien la use (por ejemplo {@link #shareFullReport()}) no tenga que
     * volver a consultar la base de datos por su cuenta y arriesgarse a que el resumen que
     * ve en pantalla y la tabla que agrega correspondan a rangos de fecha distintos. Se
     * llama con la lista vacía cuando no hay datos (quien reciba el callback decide si eso
     * le importa; {@code calculate()} ya se encargó de mostrar el mensaje correspondiente).
     */
    private void calculate(OnCalculated after) {
        long start = DateTimeUtils.startOfDay(fromDate.getTimeInMillis());
        long end = DateTimeUtils.endOfDay(toDate.getTimeInMillis());

        repository.getRangeWithDeltas(start, end, (List<MeasurementWithDeltas> rows) -> {
            if (rows.isEmpty()) {
                // Dos mensajes distintos: "la base está vacía" (nunca se ha guardado nada)
                // vs. "no hay datos en ESE periodo" (sí hay mediciones, solo que no caen en
                // el rango de fechas elegido) -- se pregunta de nuevo por los límites reales
                // en vez de asumir, porque los datos pudieron cambiar (borrar todo) desde
                // que se fijó el rango por defecto en onCreate().
                repository.getDateBounds(bounds -> binding.tvNoData.setText(
                        bounds == null ? R.string.database_empty : R.string.no_data_in_range));
                binding.tvNoData.setVisibility(View.VISIBLE);
                binding.reportContainer.setVisibility(View.GONE);
                binding.btnShareSummary.setVisibility(View.GONE);
                binding.btnShareFullReport.setVisibility(View.GONE);
                if (after != null) after.run(rows);
                return;
            }
            binding.tvNoData.setVisibility(View.GONE);
            binding.reportContainer.setVisibility(View.VISIBLE);
            binding.btnShareSummary.setVisibility(View.VISIBLE);
            binding.btnShareFullReport.setVisibility(View.VISIBLE);

            binding.tvReportTitle.setText(
                    DateTimeUtils.formatDate(fromDate.getTimeInMillis()) + " – "
                    + DateTimeUtils.formatDate(toDate.getTimeInMillis()) + "  ·  n = " + rows.size()
                    + (rows.size() == 1 ? " lectura" : " lecturas"));
            binding.tvReportFooter.setText(getString(R.string.report_footer_format,
                    getString(R.string.app_name), DateTimeUtils.formatDate(System.currentTimeMillis())));

            List<Integer> sys = new ArrayList<>();
            List<Integer> dia = new ArrayList<>();
            List<Integer> pulse = new ArrayList<>();
            List<Integer> pulsePressure = new ArrayList<>();
            // "Mañana" = medianoche a 11:59, "noche" = mediodía en adelante. Corte simple y
            // predecible; ajústalo en el código si tu rutina real difiere.
            List<Integer> morningSys = new ArrayList<>(), morningDia = new ArrayList<>(), morningPulse = new ArrayList<>();
            List<Integer> eveningSys = new ArrayList<>(), eveningDia = new ArrayList<>(), eveningPulse = new ArrayList<>();

            Calendar cal = Calendar.getInstance();
            for (MeasurementWithDeltas row : rows) {
                sys.add(row.measurement.systolic);
                dia.add(row.measurement.diastolic);
                pulse.add(row.measurement.pulse);
                pulsePressure.add(row.measurement.systolic - row.measurement.diastolic);

                cal.setTimeInMillis(row.measurement.timestampMillis);
                boolean isMorning = cal.get(Calendar.HOUR_OF_DAY) < 12;
                (isMorning ? morningSys : eveningSys).add(row.measurement.systolic);
                (isMorning ? morningDia : eveningDia).add(row.measurement.diastolic);
                (isMorning ? morningPulse : eveningPulse).add(row.measurement.pulse);
            }

            StatsResult sysStats = BPStatistics.compute(sys);
            StatsResult diaStats = BPStatistics.compute(dia);
            StatsResult pulseStats = BPStatistics.compute(pulse);
            StatsResult pulsePressureStats = BPStatistics.compute(pulsePressure);

            fillColumn(binding.tvNSys, binding.tvAvgSys, binding.tvMedianSys, binding.tvMaxSys,
                    binding.tvMinSys, binding.tvStdDevSys, sysStats);
            fillColumn(binding.tvNDia, binding.tvAvgDia, binding.tvMedianDia, binding.tvMaxDia,
                    binding.tvMinDia, binding.tvStdDevDia, diaStats);
            fillColumn(binding.tvNPulse, binding.tvAvgPulse, binding.tvMedianPulse, binding.tvMaxPulse,
                    binding.tvMinPulse, binding.tvStdDevPulse, pulseStats);

            binding.tvPulsePressureValue.setText(getString(R.string.report_pulse_pressure_value_format,
                    DF1.format(pulsePressureStats.avg), DF1.format(pulsePressureStats.median)));

            fillAmPmRow(binding.tvMorningN, binding.tvMorningSys, binding.tvMorningDia, binding.tvMorningPulse,
                    morningSys, morningDia, morningPulse);
            fillAmPmRow(binding.tvEveningN, binding.tvEveningSys, binding.tvEveningDia, binding.tvEveningPulse,
                    eveningSys, eveningDia, eveningPulse);

            binding.chartView.setData(sys, dia, sysStats.median, diaStats.median);
            binding.chartView.setDateRangeLabels(
                    DateTimeUtils.formatDate(fromDate.getTimeInMillis()),
                    DateTimeUtils.formatDate(toDate.getTimeInMillis()));

            if (after != null) after.run(rows);
        });
    }

    /** Averages only (n + AVG) — this subset is usually small, so the full 7-stat block would be noisy. */
    private void fillAmPmRow(android.widget.TextView n, android.widget.TextView sysCell,
                              android.widget.TextView diaCell, android.widget.TextView pulseCell,
                              List<Integer> sys, List<Integer> dia, List<Integer> pulse) {
        if (sys.isEmpty()) {
            n.setText("0");
            sysCell.setText("—");
            diaCell.setText("—");
            pulseCell.setText("—");
            return;
        }
        n.setText(String.valueOf(sys.size()));
        sysCell.setText(DF1.format(BPStatistics.compute(sys).avg));
        diaCell.setText(DF1.format(BPStatistics.compute(dia).avg));
        pulseCell.setText(DF1.format(BPStatistics.compute(pulse).avg));
    }

    /** Renders reportContainer (title + tables + chart, no buttons/pickers) to a PNG and shares it. */
    // Mismo tamaño carta y margen que PdfTableExporter, para que los dos documentos que
    // salen de la app (resumen y registro completo) se sientan de la misma familia.
    private static final float SUMMARY_PDF_PAGE_WIDTH = 612f;
    private static final float SUMMARY_PDF_PAGE_HEIGHT = 792f;
    private static final float SUMMARY_PDF_MARGIN = 36f;

    /**
     * El resumen ahora se comparte como PDF en vez de imagen PNG --
     * más fácil de imprimir/adjuntar para la doctora. Se sigue dibujando el mismo
     * reportContainer (con su diseño de tarjetas, logo y gráfica ya aprobado) sobre un
     * bitmap tal como antes -- eso no cambió, sigue siendo la MISMA vista, solo que ahora
     * ese bitmap se inserta como una sola página tamaño carta dentro de un PdfDocument, en
     * vez de guardarse directo como .png. Se reescala para que quepa dentro del área
     * imprimible (con el mismo margen que la tabla completa), conservando su proporción
     * original -- así el PDF se imprime bien en papel carta estándar en vez de quedar con
     * una página de alto arbitrario.
     */
    private void shareSummary() {
        // Se recalcula primero en vez de compartir lo que ya esté en pantalla a ciegas: si
        // ella cambió el rango de fechas pero no alcanzó a presionar "Calcular" antes de
        // compartir, esto asegura que lo que se comparte sea siempre el periodo que tiene
        // seleccionado en ese momento, no uno viejo. report.post(...) espera a que la vista
        // termine de re-dibujarse con los datos frescos (setText no actualiza el layout de
        // inmediato) antes de tomar la "foto" -- si se toma en el mismo instante que se
        // actualizan los textos, se arriesga a capturar un cuadro a medio actualizar.
        calculate(rows -> {
            if (rows.isEmpty()) return; // calculate() ya mostró el mensaje de "sin datos"
            View report = binding.reportContainer;
            report.post(() -> {
                if (report.getWidth() == 0 || report.getHeight() == 0) return;
                Bitmap bitmap = renderReportBitmap(report);
                PdfDocument pdfDocument = new PdfDocument();
                try {
                    appendSummaryPage(pdfDocument, bitmap, 1); // documento de una sola página, sin numerar
                    File file = PdfTableExporter.writeDocument(this, pdfDocument, "resumen_presion.pdf");
                    startActivity(PdfTableExporter.shareIntent(this, file, getString(R.string.share_summary)));
                } catch (IOException e) {
                    Toast.makeText(this, "No se pudo generar el resumen: " + e.getMessage(), Toast.LENGTH_LONG).show();
                } finally {
                    bitmap.recycle();
                }
            });
        });
    }

    /**
     * Un solo PDF con el resumen (misma tarjeta que ve en pantalla) y,
     * a continuación, la tabla completa de las lecturas del MISMO rango de fechas que se
     * está resumiendo -- así la doctora puede ver el resumen y, en las páginas siguientes
     * del mismo archivo, corroborar de dónde salen esos números ("los periodos deben ser
     * los mismos", requisito explícito del producto). Por eso este método SIEMPRE recalcula
     * primero (en vez de usar filas ya consultadas antes, o el reportContainer tal como
     * esté en pantalla en ese instante) y reutiliza la MISMA lista de filas que ese cálculo
     * usó para actualizar la pantalla -- así el bitmap del resumen y la tabla del PDF nunca
     * pueden quedar desfasados entre sí, ni siquiera si ella cambió el rango de fechas y
     * compartió sin presionar "Calcular" primero. Es a propósito el rango seleccionado en
     * pantalla (fromDate/toDate) y no "todo el historial" como en
     * HistoryActivity.sharePdfTable() -- si el resumen es de los últimos 30 días, la tabla
     * de respaldo debe ser esos mismos 30 días, no todo el histórico.
     */
    private void shareFullReport() {
        calculate(rows -> {
            if (rows.isEmpty()) return; // calculate() ya mostró el mensaje de "sin datos" correspondiente
            View report = binding.reportContainer;
            report.post(() -> {
                if (report.getWidth() == 0 || report.getHeight() == 0) return;
                Bitmap bitmap = renderReportBitmap(report);
                PdfDocument pdfDocument = new PdfDocument();
                try {
                    int totalPages = 1 + PdfTableExporter.computeTablePageCount(rows.size());
                    appendSummaryPage(pdfDocument, bitmap, totalPages);
                    PdfTableExporter.appendTablePages(pdfDocument, rows, 1, totalPages);

                    File file = PdfTableExporter.writeDocument(this, pdfDocument, "informe_completo_presion.pdf");
                    startActivity(PdfTableExporter.shareIntent(this, file, getString(R.string.share_full_report)));
                } catch (IOException e) {
                    Toast.makeText(this, "No se pudo generar el informe: " + e.getMessage(), Toast.LENGTH_LONG).show();
                } finally {
                    bitmap.recycle();
                }
            });
        });
    }

    private Bitmap renderReportBitmap(View report) {
        Bitmap bitmap = Bitmap.createBitmap(report.getWidth(), report.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas viewCanvas = new Canvas(bitmap);
        viewCanvas.drawColor(Color.parseColor("#FDFBF9")); // mismo fondo cálido de reportContainer
        report.draw(viewCanvas);
        return bitmap;
    }

    /**
     * Dibuja el bitmap del resumen como una página tamaño carta (mismo tamaño/margen que
     * {@link PdfTableExporter}), reescalado para conservar su proporción y caber en el área
     * imprimible. Cuando {@code totalPages > 1} (el informe combinado) se agrega además un
     * pie "Página 1 de N" con el mismo estilo que las páginas de la tabla, para que las dos
     * secciones se sientan parte de un solo documento; en el PDF de "solo resumen"
     * ({@code totalPages == 1}) no se numera, igual que antes.
     */
    private void appendSummaryPage(PdfDocument document, Bitmap bitmap, int totalPages) {
        float usableWidth = SUMMARY_PDF_PAGE_WIDTH - SUMMARY_PDF_MARGIN * 2;
        float usableHeight = SUMMARY_PDF_PAGE_HEIGHT - SUMMARY_PDF_MARGIN * 2;
        float scale = Math.min(usableWidth / bitmap.getWidth(), usableHeight / bitmap.getHeight());
        int drawnWidth = Math.round(bitmap.getWidth() * scale);
        int drawnHeight = Math.round(bitmap.getHeight() * scale);
        int left = Math.round(SUMMARY_PDF_MARGIN + (usableWidth - drawnWidth) / 2f);
        int top = Math.round(SUMMARY_PDF_MARGIN);

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                (int) SUMMARY_PDF_PAGE_WIDTH, (int) SUMMARY_PDF_PAGE_HEIGHT, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas pdfCanvas = page.getCanvas();
        pdfCanvas.drawColor(Color.WHITE);
        Paint scalePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Rect destRect = new Rect(left, top, left + drawnWidth, top + drawnHeight);
        pdfCanvas.drawBitmap(bitmap, null, destRect, scalePaint);

        if (totalPages > 1) {
            Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            footerPaint.setTextSize(8);
            footerPaint.setColor(Color.parseColor("#78716C"));
            String pageFooter = "Página 1 de " + totalPages;
            float footerWidth = footerPaint.measureText(pageFooter);
            float footerY = SUMMARY_PDF_PAGE_HEIGHT - SUMMARY_PDF_MARGIN + 4;
            pdfCanvas.drawText(pageFooter,
                    SUMMARY_PDF_PAGE_WIDTH - SUMMARY_PDF_MARGIN - footerWidth, footerY, footerPaint);
        }

        document.finishPage(page);
    }

    private void fillColumn(android.widget.TextView n, android.widget.TextView avg, android.widget.TextView median,
                             android.widget.TextView max, android.widget.TextView min, android.widget.TextView stdDev,
                             StatsResult stats) {
        n.setText(String.valueOf(stats.n));
        avg.setText(DF2.format(stats.avg));
        median.setText(DF1.format(stats.median));
        max.setText(String.valueOf(stats.max));
        min.setText(String.valueOf(stats.min));
        stdDev.setText(DF2.format(stats.stdDev));
    }
}
