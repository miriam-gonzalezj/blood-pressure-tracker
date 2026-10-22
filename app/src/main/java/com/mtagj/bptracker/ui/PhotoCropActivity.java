package com.mtagj.bptracker.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.mtagj.bptracker.R;
import com.mtagj.bptracker.databinding.ActivityPhotoCropBinding;
import com.mtagj.bptracker.ocr.PhotoOrientation;

import java.io.IOException;
import java.io.InputStream;

/**
 * Paso intermedio de "usar una foto existente": muestra la foto ya derecha con un recuadro
 * amarillo movible (ver {@link CropOverlayView}) para que la usuaria marque a mano dónde está
 * la pantalla del monitor, en vez de que {@code OcrHelper} lo adivine con una lista de
 * recortes centrados fijos.
 *
 * Por qué existe (punto 23 del historial del proyecto, 2026-09-16): se verificó con una foto
 * real, contra más de 80 combinaciones de recorte centrado, que NINGUNA logra los 3
 * campos cuando el aparato queda descentrado en el cuadro (mucho cable/mesa alrededor) --
 * ningún recorte que arranca del centro de la foto puede aislar una pantalla que no está en
 * el centro. Ella, viendo la foto, sí puede marcar exactamente dónde está.
 *
 * Flujo: recibe la Uri de la foto ya elegida de la galería -> la muestra ya derecha
 * (downsampleada para no cargar los ~4000x3000 originales completos solo para mostrarlos) ->
 * ella ajusta el recuadro -> "Usar este recuadro" devuelve ese rectángulo como fracción (0..1)
 * de la foto -- MainActivity llama a {@code OcrHelper.recognizeManualCrop} con eso, sobre la
 * foto ORIGINAL a resolución completa (el downsampleo es solo para mostrarla en pantalla; no
 * afecta la precisión del recorte, que se expresa en fracción, no en píxeles).
 */
public class PhotoCropActivity extends AppCompatActivity {

    public static final String EXTRA_PHOTO_URI = "extra_photo_uri";
    public static final String EXTRA_CROP_LEFT = "extra_crop_left";
    public static final String EXTRA_CROP_TOP = "extra_crop_top";
    public static final String EXTRA_CROP_RIGHT = "extra_crop_right";
    public static final String EXTRA_CROP_BOTTOM = "extra_crop_bottom";

    /** Tamaño máximo (lado más largo) de la foto downsampleada solo para MOSTRARSE en pantalla. */
    private static final int MAX_DISPLAY_DIMENSION = 1600;

    /** Recuadro inicial razonable -- el mismo tamaño que ya funciona bien como punto de
     *  partida en la mayoría de sus fotos reales (ver CROP_ATTEMPTS en OcrHelper). */
    private static final float INITIAL_WIDTH_FRACTION = 0.75f;
    private static final float INITIAL_HEIGHT_FRACTION = 0.46f;

    private ActivityPhotoCropBinding binding;
    private Uri photoUri;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPhotoCropBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setTitle(R.string.photo_crop_title);

        String uriString = getIntent().getStringExtra(EXTRA_PHOTO_URI);
        if (uriString == null) {
            finish();
            return;
        }
        photoUri = Uri.parse(uriString);

        Bitmap displayBitmap;
        try {
            displayBitmap = loadDownsampledUpright(photoUri, MAX_DISPLAY_DIMENSION);
        } catch (IOException e) {
            Toast.makeText(this, R.string.photo_load_failed, Toast.LENGTH_LONG).show();
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }
        if (displayBitmap == null) {
            Toast.makeText(this, R.string.photo_load_failed, Toast.LENGTH_LONG).show();
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        binding.imgPhotoCrop.setImageBitmap(displayBitmap);
        int bitmapW = displayBitmap.getWidth();
        int bitmapH = displayBitmap.getHeight();

        // Espera a que la vista ya tenga tamaño real (medidas de layout) antes de calcular
        // dónde cae la foto dentro de ella -- fitCenter puede dejar franjas negras a los
        // lados o arriba/abajo si la proporción de la foto no coincide con la de la vista.
        binding.imgPhotoCrop.post(() -> {
            RectF imageBounds = computeFitCenterBounds(
                    bitmapW, bitmapH, binding.imgPhotoCrop.getWidth(), binding.imgPhotoCrop.getHeight());
            binding.cropOverlay.setImageBounds(imageBounds);
            binding.cropOverlay.setInitialRect(INITIAL_WIDTH_FRACTION, INITIAL_HEIGHT_FRACTION);
        });

        binding.btnCancelCrop.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        binding.btnConfirmCrop.setOnClickListener(v -> {
            RectF fraction = binding.cropOverlay.getCropFractionOfImage();
            Intent result = new Intent();
            result.putExtra(EXTRA_CROP_LEFT, fraction.left);
            result.putExtra(EXTRA_CROP_TOP, fraction.top);
            result.putExtra(EXTRA_CROP_RIGHT, fraction.right);
            result.putExtra(EXTRA_CROP_BOTTOM, fraction.bottom);
            setResult(Activity.RESULT_OK, result);
            finish();
        });
    }

    /**
     * Dónde queda realmente dibujada una imagen de bitmapW x bitmapH dentro de una vista de
     * viewW x viewH con scaleType="fitCenter" -- la misma matemática que usa ImageView por
     * dentro, para que el recuadro pueda limitarse a la foto y no a las franjas negras.
     */
    private static RectF computeFitCenterBounds(int bitmapW, int bitmapH, int viewW, int viewH) {
        if (bitmapW <= 0 || bitmapH <= 0 || viewW <= 0 || viewH <= 0) {
            return new RectF(0, 0, viewW, viewH);
        }
        float scale = Math.min((float) viewW / bitmapW, (float) viewH / bitmapH);
        float displayedW = bitmapW * scale;
        float displayedH = bitmapH * scale;
        float left = (viewW - displayedW) / 2f;
        float top = (viewH - displayedH) / 2f;
        return new RectF(left, top, left + displayedW, top + displayedH);
    }

    /** Carga la foto ya derecha (misma corrección EXIF que PhotoOrientation.loadUprightBitmap)
     *  pero reducida a maxDimension -- solo para mostrarla; el recorte real se expresa como
     *  fracción, así que esta reducción no afecta la precisión de lo que finalmente se decodifica. */
    private Bitmap loadDownsampledUpright(Uri uri, int maxDimension) throws IOException {
        ContentResolver resolver = getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }

        int sampleSize = 1;
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (longSide / sampleSize > maxDimension) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        Bitmap sampled;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            sampled = BitmapFactory.decodeStream(in, null, options);
        }
        if (sampled == null) return null;

        int degrees = PhotoOrientation.exifRotationDegrees(this, uri);
        if (degrees == 0) return sampled;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(sampled, 0, 0, sampled.getWidth(), sampled.getHeight(), matrix, true);
        if (rotated != sampled) sampled.recycle();
        return rotated;
    }
}
