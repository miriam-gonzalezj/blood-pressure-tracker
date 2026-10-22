package com.mtagj.bptracker.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;

import com.mtagj.bptracker.core.BPReading;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.List;

/**
 * Runs {@link SevenSegmentDecoder} over an existing photo (the "usar una
 * foto existente" fallback, e.g. a picture someone else took of the
 * monitor) -- the same decoding logic the live camera scanner uses, just
 * fed a loaded Bitmap instead of a camera frame.
 *
 * Reportado (2026-09-15): esta ruta "no detecta nada" -- campos
 * vacíos y advertencia de baja certeza, con fotos reales del monitor. Dos
 * causas reales, distintas de la del escaneo en vivo:
 *
 * 1) {@code MediaStore.Images.Media.getBitmap} NO aplica la orientación
 *    EXIF -- una foto que se ve vertical en la galería puede llegar aquí
 *    como bitmap acostado si el teléfono la guardó con la bandera EXIF en
 *    vez de rotar los píxeles. Con los dígitos de lado, el filtro de forma
 *    que {@code SevenSegmentDecoder} usa internamente (privado, no incluido
 *    en este repositorio) los descarta a todos como ruido. Fix: leer la
 *    orientación EXIF y rotar antes de decodificar (ver {@link PhotoOrientation}).
 *
 * 2) A diferencia del escaneo en vivo -- guiado por un recuadro fijo en
 *    pantalla (ver {@link LiveScanAnalyzer#GUIDE_WIDTH_FRACTION}) -- una
 *    foto suelta no tiene ese encuadre: si la pantalla del monitor ocupa
 *    una fracción pequeña de la foto completa, los dígitos no llegan al
 *    tamaño mínimo relativo al cuadro que {@code SevenSegmentDecoder} exige
 *    internamente para no confundirlos con ruido. Fix en dos capas:
 *    a) {@link #recognize} prueba varios recortes CENTRADOS, de menos a más
 *       cerrados (ver {@link #CROP_ATTEMPTS}), y se queda con el que más
 *       campos logre leer.
 *    b) Cuando ni así funciona -- una foto donde el aparato queda
 *       descentrado en el cuadro, ningún recorte centrado puede aislarlo,
 *       sin importar cuántas proporciones se prueben (verificado
 *       2026-09-16 contra >80 combinaciones en una foto real) --
 *       {@link #recognizeManualCrop} decodifica el recuadro que la
 *       usuaria misma marcó a mano sobre la foto en
 *       {@link com.mtagj.bptracker.ui.PhotoCropActivity}. Un solo intento,
 *       sin adivinar, porque ya no hace falta.
 *
 * VERIFICADO con 4 fotos reales del monitor (2026-09-15/16, mismo monitor
 * Omron, a distintas distancias y encuadres), corriendo el algoritmo
 * portado a Python/OpenCV de escritorio contra los archivos reales:
 * resultado honesto, sin redondear para arriba -- 2 de las 4 decodifican
 * perfecto con {@link #recognize} sola (una de ellas solo tras corregir
 * un bug real de recorte, ver el historial de {@link #CROP_ATTEMPTS}); una
 * tercera tiene reflejos reales sobre uno o dos segmentos de un dígito en
 * esa foto concreta (confirmado visualmente, no adivinado) -- eso ya no es
 * cosa de recorte ni de orientación, es una limitación de esa toma; la
 * cuarta tiene el aparato descentrado en el cuadro, resuelto por
 * {@link #recognizeManualCrop}. Si sigue fallando con el recuadro
 * manual bien puesto sobre la pantalla, vale la pena revisar esa foto
 * para ver si es el mismo patrón de reflejo, o algo distinto.
 */
public class OcrHelper {

    public interface Listener {
        void onSuccess(BPReading reading);
        void onFailure(Exception e);
    }

    /**
     * Ventanas de recorte centrado a probar, de la menos a la más agresiva -- ancho x alto,
     * como fracción de la imagen ya orientada. La primera (1.00, 1.00) es "sin recortar",
     * el comportamiento de antes; las demás asumen que la pantalla del monitor está más o
     * menos centrada en la foto, cada vez ocupando más del cuadro analizado.
     *
     * Ajustado (2026-09-16) tras probar con una 4ta foto real: la lista anterior
     * saltaba de (0.85, 0.55) directo a (0.65, 0.38) -- ese salto de alto (0.55 -> 0.38) SÍ
     * recortaba de más para esa foto en concreto: la fila de SYS quedaba justo cortada por
     * arriba (perdía el segmento superior de uno de sus dígitos), lo que se leía mal.
     * Verificado con las 4 fotos reales que un recorte más gradual, con más pasos entre
     * medio, no comete ese corte: decodifica perfecto en esa foto donde antes
     * fallaba, sin dejar de funcionar en la que ya funcionaba. Sigue sin resolver una foto
     * donde la pantalla del monitor queda muy descentrada en el cuadro (mucho cable/mesa
     * alrededor) -- ningún recorte CENTRADO puede aislar una pantalla que no está centrada;
     * eso necesitaría que el propio recorte lo ajustara la usuaria sobre la foto, no que la
     * app lo adivine.
     */
    private static final double[][] CROP_ATTEMPTS = {
            {1.00, 1.00},
            {0.85, 0.50},
            {0.75, 0.46},
            {0.65, 0.42},
            {0.55, 0.38},
            {0.45, 0.30},
            {0.45, 0.24},
    };

    public void recognize(Context context, Uri photoUri, Listener listener) {
        try {
            Mat gray = loadUprightGray(context, photoUri);

            BPReading best = null;
            for (double[] fractions : CROP_ATTEMPTS) {
                Mat roi = cropCenter(gray, fractions[0], fractions[1]);
                List<String> rows = SevenSegmentDecoder.decode(roi);
                roi.release();
                BPReading candidate = SevenSegmentDecoder.toReading(rows);
                // Keep whichever attempt so far filled in the most fields, not just the
                // first one tried -- verified against real photos (2026-09-15) that a
                // LATER, tighter crop often reads more fields correctly than the first
                // attempt (e.g. finds SYS+DIA where the loosest crop found nothing), and
                // the old "only overwrite on a complete reading" logic was silently
                // throwing that better partial result away and reporting the first
                // attempt's worse (or empty) one instead.
                if (best == null || filledFieldCount(candidate) > filledFieldCount(best)) {
                    best = candidate;
                }
                if (candidate.isComplete()) {
                    break;
                }
            }
            gray.release();

            listener.onSuccess(best);
        } catch (IOException e) {
            listener.onFailure(e);
        }
    }

    /**
     * Refinamientos a probar DENTRO del recuadro que la usuaria ya marcó a mano -- distintos
     * de {@link #CROP_ATTEMPTS} a propósito. Verificado (2026-09-16) que un solo intento con
     * el recuadro tal cual lo dibuja ella no basta: un recuadro razonable pero no perfecto
     * alrededor de la pantalla completa (con las etiquetas "SYS/DIA/PULSE mmHg" adentro)
     * puede hacer que el cierre morfológico fusione dígitos separados en un solo borrón --
     * mismo síntoma que llevó a los recortes progresivos de {@code recognize}, pero la
     * proporción que funciona es otra: aquí el ancho es lo que sobra (esas etiquetas), no el
     * alto (las 3 filas ya están dentro de su recuadro), así que el alto se recorta poco y el
     * ancho más -- lo opuesto a CROP_ATTEMPTS, que asume que ninguna de las dos cosas se sabe
     * todavía. Confirmado con una foto real descentrada: sin este refinamiento
     * (un solo intento) no lee nada; con él, decodifica la lectura completa perfecto.
     */
    private static final double[][] MANUAL_CROP_REFINEMENTS = {
            {1.00, 1.00},
            {0.90, 0.90},
            {0.80, 0.85},
            {0.70, 0.85},
            {0.60, 0.90},
            {0.50, 0.90},
    };

    /**
     * Decodifica dentro del recuadro que la propia usuaria marcó sobre la foto en
     * {@link com.mtagj.bptracker.ui.PhotoCropActivity} -- pensado para el caso que
     * {@link #recognize} no puede resolver por más recortes fijos que se le agreguen: una
     * foto donde la pantalla del monitor queda descentrada en el cuadro (ver el punto 23 del
     * historial del proyecto). Como ella ya indicó dónde está la pantalla, no hace falta
     * adivinar la UBICACIÓN -- pero sí conviene afinar un poco el encuadre dentro de lo que
     * marcó (ver {@link #MANUAL_CROP_REFINEMENTS}), igual que en {@link #recognize}.
     *
     * @param cropFraction rectángulo marcado a mano, como fracción (0..1) de la imagen YA
     *                      orientada (misma imagen que ve en la pantalla de recorte).
     */
    public void recognizeManualCrop(Context context, Uri photoUri, RectF cropFraction, Listener listener) {
        try {
            Mat gray = loadUprightGray(context, photoUri);
            Mat manualCrop = cropToFraction(gray, cropFraction);
            gray.release();

            BPReading best = null;
            for (double[] fractions : MANUAL_CROP_REFINEMENTS) {
                Mat roi = cropCenter(manualCrop, fractions[0], fractions[1]);
                List<String> rows = SevenSegmentDecoder.decode(roi);
                roi.release();
                BPReading candidate = SevenSegmentDecoder.toReading(rows);
                if (best == null || filledFieldCount(candidate) > filledFieldCount(best)) {
                    best = candidate;
                }
                if (candidate.isComplete()) {
                    break;
                }
            }
            manualCrop.release();

            listener.onSuccess(best);
        } catch (IOException e) {
            listener.onFailure(e);
        }
    }

    /** Carga la foto ya derecha (ver {@link PhotoOrientation}) y en escala de grises, lista para decodificar. */
    private static Mat loadUprightGray(Context context, Uri photoUri) throws IOException {
        Bitmap bitmap = PhotoOrientation.loadUprightBitmap(context, photoUri);
        Mat rgba = new Mat();
        Utils.bitmapToMat(bitmap, rgba);
        Mat gray = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
        rgba.release();
        return gray;
    }

    /** Cuántos de los 3 campos (sys/dia/pulso) trae resueltos -- usado para comparar intentos de recorte. */
    private static int filledFieldCount(BPReading reading) {
        int n = 0;
        if (reading.systolic != null) n++;
        if (reading.diastolic != null) n++;
        if (reading.pulse != null) n++;
        return n;
    }

    /** Recorte centrado a la fracción de ancho/alto pedida -- ver CROP_ATTEMPTS arriba. */
    private static Mat cropCenter(Mat mat, double widthFraction, double heightFraction) {
        int w = mat.cols();
        int h = mat.rows();
        int cropW = (int) Math.round(w * widthFraction);
        int cropH = (int) Math.round(h * heightFraction);
        int x0 = Math.max(0, (w - cropW) / 2);
        int y0 = Math.max(0, (h - cropH) / 2);
        int x1 = Math.min(w, x0 + cropW);
        int y1 = Math.min(h, y0 + cropH);
        return mat.submat(y0, y1, x0, x1).clone();
    }

    /** Recorte a un rectángulo arbitrario (no necesariamente centrado), en fracciones 0..1 -- ver recognizeManualCrop. */
    private static Mat cropToFraction(Mat mat, RectF fraction) {
        int w = mat.cols();
        int h = mat.rows();
        int x0 = clamp((int) Math.round(fraction.left * w), 0, w - 1);
        int y0 = clamp((int) Math.round(fraction.top * h), 0, h - 1);
        int x1 = clamp((int) Math.round(fraction.right * w), x0 + 1, w);
        int y1 = clamp((int) Math.round(fraction.bottom * h), y0 + 1, h);
        return mat.submat(y0, y1, x0, x1).clone();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
