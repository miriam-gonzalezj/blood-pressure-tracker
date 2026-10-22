package com.mtagj.bptracker.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * A draggable/resizable rectangle drawn over a photo, so the user can mark exactly where
 * the monitor's screen is -- see {@link PhotoCropActivity}. Same idea as the fixed yellow
 * guide box on the live-scan screen (see activity_main.xml's scanGuideBox), except here
 * SHE moves it, because the photo is already taken and the app can no longer ask her to
 * reframe it.
 *
 * Coordinates of {@link #cropRect} are in this VIEW's own pixels. {@link PhotoCropActivity}
 * is responsible for translating that into a fraction of the actual photo, since the
 * displayed image (fitCenter inside an ImageView the same size as this view) is normally
 * letterboxed and does not fill 100% of the view.
 */
public class CropOverlayView extends View {

    private static final float MIN_SIZE_DP = 60f;
    private static final float HANDLE_TOUCH_RADIUS_DP = 28f;
    private static final float HANDLE_RADIUS_DP = 10f;

    private enum DragMode {NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT}

    private final RectF cropRect = new RectF();
    /** Dónde cae realmente la foto dentro de esta vista (fitCenter puede dejar franjas negras a
     *  los lados o arriba/abajo) -- el recuadro no debe poder salirse de aquí. Por defecto, toda
     *  la vista, hasta que PhotoCropActivity la ajuste con setImageBounds() una vez que conoce el
     *  tamaño real de la foto. */
    private final RectF imageBounds = new RectF();
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float minSizePx;
    private float handleTouchRadiusPx;
    private float handleRadiusPx;

    private DragMode dragMode = DragMode.NONE;
    private float lastTouchX;
    private float lastTouchY;

    public CropOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        minSizePx = MIN_SIZE_DP * density;
        handleTouchRadiusPx = HANDLE_TOUCH_RADIUS_DP * density;
        handleRadiusPx = HANDLE_RADIUS_DP * density;

        dimPaint.setColor(Color.parseColor("#99000000"));

        borderPaint.setColor(Color.parseColor("#FFEB3B")); // mismo amarillo que scan_guide_border.xml
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f * density);

        handlePaint.setColor(Color.parseColor("#FFEB3B"));
        handlePaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Dónde cae la foto realmente dentro de esta vista (puede haber franjas negras por
     * fitCenter) -- el recuadro se coloca y se limita a moverse dentro de este rectángulo,
     * nunca sobre las franjas negras. Debe llamarse antes de {@link #setInitialRect}.
     */
    public void setImageBounds(RectF bounds) {
        imageBounds.set(bounds);
        invalidate();
    }

    /** Coloca un recuadro inicial razonable, centrado dentro de la foto -- ella lo ajusta desde ahí. */
    public void setInitialRect(float widthFraction, float heightFraction) {
        RectF bounds = effectiveImageBounds();
        float cropW = bounds.width() * widthFraction;
        float cropH = bounds.height() * heightFraction;
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();
        cropRect.set(centerX - cropW / 2f, centerY - cropH / 2f, centerX + cropW / 2f, centerY + cropH / 2f);
        invalidate();
    }

    private RectF effectiveImageBounds() {
        if (!imageBounds.isEmpty()) return imageBounds;
        return new RectF(0, 0, getWidth(), getHeight());
    }

    /** Recuadro actual, en píxeles de ESTA vista -- normalmente no hace falta, ver getCropFractionOfImage(). */
    public RectF getCropRect() {
        return new RectF(cropRect);
    }

    /**
     * El recuadro actual como fracción (0..1) de la FOTO -- no de esta vista -- lista para
     * pasarle a {@code OcrHelper.recognizeManualCrop}. Usa {@link #imageBounds} (puesto por
     * PhotoCropActivity) para descontar cualquier franja negra del fitCenter.
     */
    public RectF getCropFractionOfImage() {
        RectF bounds = effectiveImageBounds();
        float left = (cropRect.left - bounds.left) / bounds.width();
        float top = (cropRect.top - bounds.top) / bounds.height();
        float right = (cropRect.right - bounds.left) / bounds.width();
        float bottom = (cropRect.bottom - bounds.top) / bounds.height();
        return new RectF(
                clamp01(left), clamp01(top), clamp01(right), clamp01(bottom));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cropRect.isEmpty()) return;

        // Oscurece todo menos el recuadro -- cuatro franjas en vez de un canvas.clipOutRect
        // (deprecado/inconsistente entre versiones de Android), más simple y confiable.
        canvas.drawRect(0, 0, getWidth(), cropRect.top, dimPaint);
        canvas.drawRect(0, cropRect.bottom, getWidth(), getHeight(), dimPaint);
        canvas.drawRect(0, cropRect.top, cropRect.left, cropRect.bottom, dimPaint);
        canvas.drawRect(cropRect.right, cropRect.top, getWidth(), cropRect.bottom, dimPaint);

        canvas.drawRect(cropRect, borderPaint);
        canvas.drawCircle(cropRect.left, cropRect.top, handleRadiusPx, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.top, handleRadiusPx, handlePaint);
        canvas.drawCircle(cropRect.left, cropRect.bottom, handleRadiusPx, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadiusPx, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragMode = detectDragMode(x, y);
                lastTouchX = x;
                lastTouchY = y;
                return dragMode != DragMode.NONE;

            case MotionEvent.ACTION_MOVE:
                if (dragMode == DragMode.NONE) return false;
                applyDrag(x - lastTouchX, y - lastTouchY);
                lastTouchX = x;
                lastTouchY = y;
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = DragMode.NONE;
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private DragMode detectDragMode(float x, float y) {
        if (near(x, y, cropRect.left, cropRect.top)) return DragMode.TOP_LEFT;
        if (near(x, y, cropRect.right, cropRect.top)) return DragMode.TOP_RIGHT;
        if (near(x, y, cropRect.left, cropRect.bottom)) return DragMode.BOTTOM_LEFT;
        if (near(x, y, cropRect.right, cropRect.bottom)) return DragMode.BOTTOM_RIGHT;
        if (cropRect.contains(x, y)) return DragMode.MOVE;
        return DragMode.NONE;
    }

    private boolean near(float x, float y, float targetX, float targetY) {
        float dx = x - targetX;
        float dy = y - targetY;
        return (dx * dx + dy * dy) <= handleTouchRadiusPx * handleTouchRadiusPx;
    }

    private void applyDrag(float dx, float dy) {
        RectF bounds = effectiveImageBounds();
        switch (dragMode) {
            case MOVE: {
                float newLeft = clamp(cropRect.left + dx, bounds.left, bounds.right - cropRect.width());
                float newTop = clamp(cropRect.top + dy, bounds.top, bounds.bottom - cropRect.height());
                cropRect.offsetTo(newLeft, newTop);
                break;
            }
            case TOP_LEFT: {
                float left = clamp(cropRect.left + dx, bounds.left, cropRect.right - minSizePx);
                float top = clamp(cropRect.top + dy, bounds.top, cropRect.bottom - minSizePx);
                cropRect.left = left;
                cropRect.top = top;
                break;
            }
            case TOP_RIGHT: {
                float right = clamp(cropRect.right + dx, cropRect.left + minSizePx, bounds.right);
                float top = clamp(cropRect.top + dy, bounds.top, cropRect.bottom - minSizePx);
                cropRect.right = right;
                cropRect.top = top;
                break;
            }
            case BOTTOM_LEFT: {
                float left = clamp(cropRect.left + dx, bounds.left, cropRect.right - minSizePx);
                float bottom = clamp(cropRect.bottom + dy, cropRect.top + minSizePx, bounds.bottom);
                cropRect.left = left;
                cropRect.bottom = bottom;
                break;
            }
            case BOTTOM_RIGHT: {
                float right = clamp(cropRect.right + dx, cropRect.left + minSizePx, bounds.right);
                float bottom = clamp(cropRect.bottom + dy, cropRect.top + minSizePx, bounds.bottom);
                cropRect.right = right;
                cropRect.bottom = bottom;
                break;
            }
            case NONE:
            default:
                break;
        }
    }

    private static float clamp(float value, float min, float max) {
        if (max < min) return min; // vista aún sin medir bien -- no romper, solo no mover nada raro
        return Math.max(min, Math.min(max, value));
    }
}
