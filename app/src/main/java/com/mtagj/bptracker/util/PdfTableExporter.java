package com.mtagj.bptracker.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.mtagj.bptracker.data.MeasurementWithDeltas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Exports the FULL, record-by-record list as a compact PDF table -- letra
 * chica, en columnas, mismas columnas que CsvExporter (que a su vez replica
 * su hoja de Excel) -- para que la endocrinóloga pueda ver cada lectura
 * individual, no solo el resumen agregado de AnalyticsActivity. Se paginó
 * automáticamente (tamaño carta), repitiendo encabezado y numeración de
 * página en cada hoja, así que funciona igual de bien con 20 lecturas que
 * con varios cientos.
 *
 * Estilo: encabezado con título + fecha de generación y una regla de acento
 * (sin el nombre de la app en rojo arriba -- se quitó a propósito), filas
 * alternadas (cebra) en vez de una
 * línea bajo cada renglón, columnas numéricas alineadas a la derecha, pie de
 * página con fecha de generación y numeración -- pensado para que se vea
 * como un reporte de verdad, ya que va dirigido a una doctora que puede no
 * estar familiarizada con la app.
 */
public final class PdfTableExporter {

    private static final float PAGE_WIDTH = 612f;   // carta, 8.5in @ 72pt/in
    private static final float PAGE_HEIGHT = 792f;  // carta, 11in @ 72pt/in
    private static final float MARGIN = 36f;
    private static final float HEADER_BLOCK_HEIGHT = 46f; // título + fecha/página + regla de acento
    private static final float FOOTER_BLOCK_HEIGHT = 22f;
    private static final float ROW_HEIGHT = 16f;
    private static final float TABLE_HEADER_HEIGHT = 20f;

    private static final int COLOR_BRAND = Color.parseColor("#D62839");
    private static final int COLOR_DARK = Color.parseColor("#212121");
    private static final int COLOR_MUTED = Color.parseColor("#78716C");
    private static final int COLOR_HEADER_BG = Color.parseColor("#F2EFEC");
    private static final int COLOR_ZEBRA = Color.parseColor("#F7F5F3");

    private static final String[] HEADERS = {
            "Fecha", "Hora", "Sist", "Diast", "Pulso", "Brazo", "Altitud", "Días", "ΔSist", "ΔDiast", "Nota"
    };
    // Suma = 540pt = ancho de página carta (612) menos márgenes de 36pt a cada lado.
    private static final float[] COL_WIDTHS = {
            62, 40, 34, 34, 40, 62, 80, 34, 38, 38, 78
    };
    // Columnas numéricas: se alinean a la derecha en vez de a la izquierda (Sist, Diast, Pulso, Días, ΔSist, ΔDiast).
    private static final boolean[] COL_RIGHT_ALIGN = {
            false, false, true, true, true, false, false, true, true, true, false
    };

    private static final SimpleDateFormat GENERATED_FORMAT =
            new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    private PdfTableExporter() {
    }

    public static File exportAndSave(Context context, List<MeasurementWithDeltas> oldestFirst) throws IOException {
        PdfDocument document = new PdfDocument();
        appendTablePages(document, oldestFirst, 0, computeTablePageCount(oldestFirst.size()));
        return writeDocument(context, document, "registros_completos_presion.pdf");
    }

    /** Cuántas páginas ocupa la tabla completa para {@code rowCount} filas, a este tamaño de letra/fila. */
    public static int computeTablePageCount(int rowCount) {
        float tableTop = MARGIN + HEADER_BLOCK_HEIGHT;
        float usableHeight = PAGE_HEIGHT - tableTop - FOOTER_BLOCK_HEIGHT - MARGIN;
        int rowsPerPage = Math.max(1, (int) ((usableHeight - TABLE_HEADER_HEIGHT) / ROW_HEIGHT));
        return Math.max(1, (int) Math.ceil(rowCount / (double) rowsPerPage));
    }

    /**
     * Agrega las páginas de la tabla completa a un {@link PdfDocument} ya existente, en vez
     * de crear uno propio y cerrarlo -- así se puede usar tanto para el PDF de "solo tabla"
     * ({@code pageOffset = 0}, {@code totalPages = computeTablePageCount(...)}) como para el
     * informe combinado que arma {@code AnalyticsActivity} (resumen + historial en un solo
     * archivo, para llevarlo junto a la doctora), donde la tabla va
     * DESPUÉS de la(s) página(s) de resumen. {@code pageOffset} es cuántas páginas ya
     * existen antes de esta tabla (para que la numeración "Página X de Y" del pie sea la del
     * documento completo, no solo de esta sección) y {@code totalPages} es el total de
     * páginas de TODO el documento final.
     */
    public static void appendTablePages(PdfDocument document, List<MeasurementWithDeltas> oldestFirst,
                                         int pageOffset, int totalPages) {
        Paint titlePaint = paint(13, true, COLOR_DARK);
        Paint generatedPaint = paint(8, false, COLOR_MUTED);
        Paint headerPaint = paint(8, true, COLOR_DARK);
        Paint cellPaint = paint(8, false, COLOR_DARK);
        Paint footerPaint = paint(8, false, COLOR_MUTED);

        Paint accentFill = new Paint();
        accentFill.setColor(COLOR_BRAND);
        Paint headerBgFill = new Paint();
        headerBgFill.setColor(COLOR_HEADER_BG);
        Paint zebraFill = new Paint();
        zebraFill.setColor(COLOR_ZEBRA);
        Paint hairline = new Paint();
        hairline.setColor(Color.LTGRAY);
        hairline.setStrokeWidth(0.75f);

        String generatedAt = GENERATED_FORMAT.format(new Date());

        float tableTop = MARGIN + HEADER_BLOCK_HEIGHT;
        float usableHeight = PAGE_HEIGHT - tableTop - FOOTER_BLOCK_HEIGHT - MARGIN;
        int rowsPerPage = Math.max(1, (int) ((usableHeight - TABLE_HEADER_HEIGHT) / ROW_HEIGHT));
        int tablePages = computeTablePageCount(oldestFirst.size());

        int index = 0;
        for (int localPageNum = 1; localPageNum <= tablePages; localPageNum++) {
            int printedPageNum = pageOffset + localPageNum;
            PdfDocument.PageInfo pageInfo =
                    new PdfDocument.PageInfo.Builder((int) PAGE_WIDTH, (int) PAGE_HEIGHT, printedPageNum).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);

            // --- Encabezado: título en su propia línea, fecha/página debajo (sin marca) ---
            // El título completo ("Registro completo de mediciones — Presión arterial") ya
            // ocupa casi todo el ancho de la página a 13pt bold, así que va solo en su línea:
            // ponerlo junto a la fecha de generación (como se probó primero) hacía que se
            // encimaran -- se detectó al renderizar el preview, no a simple vista en el código.
            canvas.drawText("Registro completo de mediciones — Presión arterial", MARGIN, MARGIN + 14, titlePaint);

            String generatedLabel = "Generado el " + generatedAt
                    + (totalPages > 1 ? "   ·   página " + printedPageNum + "/" + totalPages : "");
            float generatedWidth = generatedPaint.measureText(generatedLabel);
            canvas.drawText(generatedLabel, PAGE_WIDTH - MARGIN - generatedWidth, MARGIN + 30, generatedPaint);

            canvas.drawRect(MARGIN, MARGIN + 36, MARGIN + 30, MARGIN + 38.5f, accentFill);

            // --- Encabezado de tabla ---
            float y = tableTop;
            canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + TABLE_HEADER_HEIGHT, headerBgFill);
            drawRow(canvas, HEADERS, y, TABLE_HEADER_HEIGHT, headerPaint);
            y += TABLE_HEADER_HEIGHT;

            int rowsOnThisPage = 0;
            while (index < oldestFirst.size() && rowsOnThisPage < rowsPerPage) {
                if (index % 2 == 0) {
                    canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + ROW_HEIGHT, zebraFill);
                }
                drawRow(canvas, toCells(oldestFirst.get(index)), y, ROW_HEIGHT, cellPaint);
                y += ROW_HEIGHT;
                index++;
                rowsOnThisPage++;
            }
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, hairline);

            // --- Pie de página ---
            float footerY = PAGE_HEIGHT - MARGIN + 4;
            canvas.drawText("Generado con Latido Miri", MARGIN, footerY, footerPaint);
            String pageFooter = "Página " + printedPageNum + " de " + totalPages;
            float pageFooterWidth = footerPaint.measureText(pageFooter);
            canvas.drawText(pageFooter, PAGE_WIDTH - MARGIN - pageFooterWidth, footerY, footerPaint);

            document.finishPage(page);
        }
    }

    /** Escribe el documento a un archivo dentro de la carpeta de exportación y lo cierra. */
    public static File writeDocument(Context context, PdfDocument document, String fileName) throws IOException {
        File dir = new File(context.getCacheDir(), "exports");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            document.writeTo(out);
        } finally {
            document.close();
        }
        return file;
    }

    public static Intent shareIntent(Context context, File pdfFile) {
        return shareIntent(context, pdfFile, "Compartir tabla en PDF");
    }

    public static Intent shareIntent(Context context, File pdfFile, String chooserTitle) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", pdfFile);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(intent, chooserTitle);
    }

    private static String[] toCells(MeasurementWithDeltas row) {
        long ts = row.measurement.timestampMillis;
        return new String[]{
                DateTimeUtils.formatDate(ts),
                DateTimeUtils.formatTime(ts),
                String.valueOf(row.measurement.systolic),
                String.valueOf(row.measurement.diastolic),
                String.valueOf(row.measurement.pulse),
                nullToEmpty(row.measurement.arm),
                nullToEmpty(row.measurement.altitude),
                row.daysElapsed == null ? "" : String.valueOf(row.daysElapsed),
                row.varSystolic == null ? "" : signed(row.varSystolic),
                row.varDiastolic == null ? "" : signed(row.varDiastolic),
                nullToEmpty(row.measurement.note),
        };
    }

    private static String signed(int value) {
        return (value >= 0 ? "+" : "") + value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Dibuja una fila de celdas. Las columnas numéricas (ver COL_RIGHT_ALIGN) se alinean
     * a la derecha, más fácil de comparar de un vistazo que si todo quedara pegado a la
     * izquierda. Un texto que no cabe (típicamente "Nota", que es libre) se trunca con
     * "…" en vez de dejar que canvas.clipRect lo corte a media palabra -- se probó con
     * una nota larga real ("después de caminar") que sí desborda la columna a 8pt.
     */
    private static void drawRow(Canvas canvas, String[] cells, float y, float rowHeight, Paint paint) {
        float x = MARGIN;
        float baseline = y + rowHeight - 6f;
        for (int i = 0; i < cells.length && i < COL_WIDTHS.length; i++) {
            String text = cells[i] == null ? "" : cells[i];
            float available = COL_WIDTHS[i] - 6f;
            text = ellipsize(text, paint, available);
            canvas.save();
            canvas.clipRect(x, y, x + COL_WIDTHS[i], y + rowHeight);
            if (i < COL_RIGHT_ALIGN.length && COL_RIGHT_ALIGN[i]) {
                float textWidth = paint.measureText(text);
                canvas.drawText(text, x + COL_WIDTHS[i] - textWidth - 4, baseline, paint);
            } else {
                canvas.drawText(text, x + 3, baseline, paint);
            }
            canvas.restore();
            x += COL_WIDTHS[i];
        }
    }

    /** Recorta texto que no cabe en maxWidth y le pone "…" al final, en vez de cortarlo a media letra. */
    private static String ellipsize(String text, Paint paint, float maxWidth) {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text;
        String ellipsis = "…";
        float ellipsisWidth = paint.measureText(ellipsis);
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (paint.measureText(text, 0, mid) + ellipsisWidth <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo == 0 ? ellipsis : text.substring(0, lo) + ellipsis;
    }

    private static Paint paint(float sizePt, boolean bold, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(sizePt);
        p.setColor(color);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }
}
