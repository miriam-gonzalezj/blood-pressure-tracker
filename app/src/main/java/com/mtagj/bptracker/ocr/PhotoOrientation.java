package com.mtagj.bptracker.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shared EXIF-rotation logic for a photo picked from the gallery --
 * {@link MediaStore.Images.Media#getBitmap} returns the raw, UN-rotated
 * pixel buffer (see {@link OcrHelper}'s class doc for why that broke
 * decoding), so anything that loads such a photo needs this same fix.
 * Pulled out of {@link OcrHelper} so {@link com.mtagj.bptracker.ui.PhotoCropActivity}
 * (which needs to show the photo upright to the user, not decode it) uses the
 * exact same rotation as the decoder does -- otherwise the crop box she draws
 * on the screen would not land on the same region the decoder analyzes.
 */
public class PhotoOrientation {

    private PhotoOrientation() {
    }

    /** Grados a rotar EN SENTIDO HORARIO para enderezar la imagen, según la bandera EXIF de la foto. */
    public static int exifRotationDegrees(Context context, Uri photoUri) {
        try (InputStream in = context.getContentResolver().openInputStream(photoUri)) {
            if (in == null) return 0;
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            // Sin dato EXIF legible -- se sigue con la imagen tal como llegó en vez de fallar
            // toda la lectura por esto; en el peor caso queda en la misma situación que antes
            // de este fix.
            return 0;
        }
    }

    /**
     * Carga la foto ya derecha, lista para MOSTRARSE en pantalla (para decodificarla se usa
     * {@code OcrHelper}, que trabaja con Mat en escala de grises, no con este Bitmap a color).
     */
    public static Bitmap loadUprightBitmap(Context context, Uri photoUri) throws IOException {
        Bitmap bitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), photoUri);
        int degrees = exifRotationDegrees(context, photoUri);
        if (degrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }
}
