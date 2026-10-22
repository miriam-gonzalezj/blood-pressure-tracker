package com.mtagj.bptracker.ui;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.mtagj.bptracker.R;
import com.mtagj.bptracker.core.BPReading;
import com.mtagj.bptracker.data.Measurement;
import com.mtagj.bptracker.data.MeasurementRepository;
import com.mtagj.bptracker.databinding.ActivityMainBinding;
import com.mtagj.bptracker.location.AltitudeHelper;
import com.mtagj.bptracker.ocr.LiveScanAnalyzer;
import com.mtagj.bptracker.ocr.OcrHelper;
import com.mtagj.bptracker.util.DateTimeUtils;

import org.opencv.android.OpenCVLoader;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens straight into a live camera view aimed at the monitor (nothing is
 * ever photographed or saved — frames are analyzed in memory). As soon as a
 * reading is read consistently across a few frames, the screen switches to
 * a review form (values, date/time, altitude, arm, note) with a SAVE
 * button. Saving returns to the live scanner, ready for the next reading.
 */
public class MainActivity extends AppCompatActivity implements LiveScanAnalyzer.Listener {

    private ActivityMainBinding binding;
    private MeasurementRepository repository;
    private final OcrHelper ocrHelper = new OcrHelper();
    private final AltitudeHelper altitudeHelper = new AltitudeHelper();
    private final LiveScanAnalyzer scanAnalyzer = new LiveScanAnalyzer(this);
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    private Calendar selectedDateTime = Calendar.getInstance();

    private ActivityResultLauncher<String> requestCameraPermission;
    private ActivityResultLauncher<String> requestLocationPermission;
    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<Intent> cropPhotoLauncher;
    /** Foto que se mandó a PhotoCropActivity -- esa pantalla solo devuelve el recuadro
     *  elegido, no la foto, así que hay que recordar de cuál foto era. */
    private Uri pendingCropPhotoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "No se pudo cargar OpenCV — la detección automática no va a funcionar.", Toast.LENGTH_LONG).show();
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        repository = new MeasurementRepository(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        registerLaunchers();
        setUpDateTimeButtons();

        binding.btnManualEntry.setOnClickListener(v -> enterManually());
        binding.btnPickGallery.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        binding.btnSave.setOnClickListener(v -> onSaveClicked());
        binding.btnRescan.setOnClickListener(v -> showScanningState());
        binding.tvScanStatus.setOnClickListener(v -> ensureCameraPermissionThenStart());

        showScanningState();
        ensureCameraPermissionThenStart();
        fetchAltitudeBestEffort();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@androidx.annotation.NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_history) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        } else if (id == R.id.action_analytics) {
            startActivity(new Intent(this, AnalyticsActivity.class));
            return true;
        } else if (id == R.id.action_capture) {
            return true; // already here
        }
        return super.onOptionsItemSelected(item);
    }

    // -----------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------

    private void registerLaunchers() {
        requestCameraPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) startCamera();
                    else {
                        Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
                        binding.tvScanStatus.setText(R.string.camera_permission_needed);
                    }
                });

        requestLocationPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> fetchAltitudeBestEffort());

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) launchPhotoCrop(uri);
                });

        // Ver PhotoCropActivity -- ella marca a mano dónde está la pantalla del monitor en
        // la foto ya elegida, en vez de que OcrHelper adivine con una lista de recortes fijos
        // (necesario para una foto donde el aparato queda descentrado en el cuadro; ningún
        // recorte centrado puede acertarle a eso, sin importar cuántas proporciones se prueben).
        cropPhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null
                            || pendingCropPhotoUri == null) {
                        return;
                    }
                    Intent data = result.getData();
                    RectF cropFraction = new RectF(
                            data.getFloatExtra(PhotoCropActivity.EXTRA_CROP_LEFT, 0f),
                            data.getFloatExtra(PhotoCropActivity.EXTRA_CROP_TOP, 0f),
                            data.getFloatExtra(PhotoCropActivity.EXTRA_CROP_RIGHT, 1f),
                            data.getFloatExtra(PhotoCropActivity.EXTRA_CROP_BOTTOM, 1f));
                    onGalleryPhotoPicked(pendingCropPhotoUri, cropFraction);
                });
    }

    private void launchPhotoCrop(Uri uri) {
        pendingCropPhotoUri = uri;
        Intent intent = new Intent(this, PhotoCropActivity.class);
        intent.putExtra(PhotoCropActivity.EXTRA_PHOTO_URI, uri.toString());
        cropPhotoLauncher.launch(intent);
    }

    private void setUpDateTimeButtons() {
        binding.btnPickDate.setOnClickListener(v -> {
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                selectedDateTime.set(Calendar.YEAR, year);
                selectedDateTime.set(Calendar.MONTH, month);
                selectedDateTime.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                refreshDateTimeButtonLabels();
            }, selectedDateTime.get(Calendar.YEAR), selectedDateTime.get(Calendar.MONTH),
                    selectedDateTime.get(Calendar.DAY_OF_MONTH)).show();
        });
        binding.btnPickTime.setOnClickListener(v -> {
            new TimePickerDialog(this, (view, hourOfDay, minute) -> {
                selectedDateTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
                selectedDateTime.set(Calendar.MINUTE, minute);
                refreshDateTimeButtonLabels();
            }, selectedDateTime.get(Calendar.HOUR_OF_DAY), selectedDateTime.get(Calendar.MINUTE), true).show();
        });
    }

    private void refreshDateTimeButtonLabels() {
        long millis = selectedDateTime.getTimeInMillis();
        binding.btnPickDate.setText(DateTimeUtils.formatDate(millis));
        binding.btnPickTime.setText(DateTimeUtils.formatTime(millis));
    }

    // -----------------------------------------------------------------
    // Screen state: scanning <-> review
    // -----------------------------------------------------------------

    private void showScanningState() {
        binding.reviewContainer.setVisibility(View.GONE);
        binding.cameraContainer.setVisibility(View.VISIBLE);
        binding.tvScanStatus.setText(R.string.scan_hint);
        scanAnalyzer.reset();
        scanAnalyzer.setPaused(false);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    private void showReviewState() {
        scanAnalyzer.setPaused(true);
        if (cameraProvider != null) cameraProvider.unbindAll(); // release the camera while reviewing/saving
        binding.cameraContainer.setVisibility(View.GONE);
        binding.reviewContainer.setVisibility(View.VISIBLE);
    }

    private void enterManually() {
        selectedDateTime = Calendar.getInstance();
        refreshDateTimeButtonLabels();
        clearForm();
        // BUG REAL, encontrado 2026-09-23 a partir de un reporte de uso real (la altitud no
        // se podía calcular de otra forma al capturar a mano):
        // clearForm() (arriba) siempre deja etAltitude en "", incluyendo cuando la altitud
        // por GPS ya se había obtenido en segundo plano al abrir la app (fetchAltitudeBestEffort()
        // en onCreate()). Como esa consulta GPS normalmente ya terminó para cuando se toca
        // "Ingresar manualmente" -- sobre todo si hay un fix reciente en caché (maxUpdateAge de
        // 2 minutos) --, el campo quedaba vacío para siempre en este flujo específico: nada
        // lo volvía a pedir. onSaveClicked() ya tenía el patrón correcto (clearForm() seguido
        // de fetchAltitudeBestEffort(), ver comentario ahí) para la altitud de la SIGUIENTE
        // lectura tras guardar -- pero entrar a captura manual nunca pasaba por ese mismo par.
        // Fix: repetir aquí el mismo par clearForm()+fetchAltitudeBestEffort() que ya usa
        // onSaveClicked(), para que la captura manual también reciba su propio intento de
        // altitud por GPS en vez de quedar en blanco por diseño.
        fetchAltitudeBestEffort();
        showReviewState();
    }

    /** Deja los campos de captura totalmente en blanco -- sin valores de una lectura anterior. */
    private void clearForm() {
        binding.etSystolic.setText("");
        binding.etDiastolic.setText("");
        binding.etPulse.setText("");
        binding.etAltitude.setText("");
        binding.etNote.setText("");
        binding.tvOcrHint.setVisibility(View.GONE);
        binding.tvLowConfidence.setVisibility(View.GONE);
    }

    // -----------------------------------------------------------------
    // Live camera scanning (CameraX + OpenCV segment decoding, no photo ever saved)
    // -----------------------------------------------------------------

    private void ensureCameraPermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                binding.tvScanStatus.setText("No se pudo abrir la cámara: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, scanAnalyzer);

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
    }

    @Override
    public void onFramePreview(BPReading latestGuess) {
        runOnUiThread(() -> {
            if (binding.cameraContainer.getVisibility() != View.VISIBLE) return;
            // hasSysDia(), not isComplete() -- matches LiveScanAnalyzer's actual confirmation
            // gate (2026-09-23), so this status text doesn't keep saying "buscando..." on a
            // frame that's actually about to confirm (SYS/DIA read, PULSE optional).
            binding.tvScanStatus.setText(latestGuess.hasSysDia()
                    ? getString(R.string.scan_status_found)
                    : getString(R.string.scan_status_searching));
        });
    }

    @Override
    public void onReadingStabilized(BPReading reading) {
        runOnUiThread(() -> {
            selectedDateTime = Calendar.getInstance(); // capture time = now, it's a live reading
            refreshDateTimeButtonLabels();
            applyReadingToForm(reading);
            showReviewState();
        });
    }

    // -----------------------------------------------------------------
    // Fallback: pick an existing photo (e.g. one someone else sent you)
    // -----------------------------------------------------------------

    private void onGalleryPhotoPicked(Uri uri, RectF cropFraction) {
        Toast.makeText(this, "Analizando foto…", Toast.LENGTH_SHORT).show();
        ocrHelper.recognizeManualCrop(this, uri, cropFraction, new OcrHelper.Listener() {
            @Override
            public void onSuccess(BPReading reading) {
                selectedDateTime = Calendar.getInstance();
                refreshDateTimeButtonLabels();
                applyReadingToForm(reading);
                showReviewState();
            }

            @Override
            public void onFailure(Exception e) {
                Toast.makeText(MainActivity.this,
                        "No se pudo leer esa foto, ingresa los valores a mano.", Toast.LENGTH_LONG).show();
                enterManually();
            }
        });
    }

    private void applyReadingToForm(BPReading reading) {
        // Siempre se escribe algo en los tres campos -- "" cuando ese valor no se pudo
        // leer -- en vez de solo sobreescribir los que sí se detectaron. Antes, un campo
        // no detectado (reading.systolic == null, por ejemplo) simplemente no se tocaba,
        // así que se quedaba mostrando lo que ya hubiera en el campo de un intento anterior
        // (otra foto, u otra lectura en vivo). Eso es justo lo que se reportó: la pantalla
        // de revisión seguía mostrando los mismos valores una y otra vez con fotos distintas,
        // junto con la advertencia de baja certeza -- esos eran valores viejos de una lectura
        // anterior que nunca se limpiaron, no el resultado real (y probablemente fallido)
        // del intento actual.
        binding.etSystolic.setText(reading.systolic != null ? String.valueOf(reading.systolic) : "");
        binding.etDiastolic.setText(reading.diastolic != null ? String.valueOf(reading.diastolic) : "");
        binding.etPulse.setText(reading.pulse != null ? String.valueOf(reading.pulse) : "");

        binding.tvOcrHint.setVisibility(View.VISIBLE);
        binding.tvLowConfidence.setVisibility(reading.isHighConfidence() && reading.isComplete() ? View.GONE : View.VISIBLE);
    }

    // -----------------------------------------------------------------
    // Altitude (best effort, never blocks the flow)
    // -----------------------------------------------------------------

    private void fetchAltitudeBestEffort() {
        if (!AltitudeHelper.hasLocationPermission(this)) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        binding.progressAltitude.setVisibility(View.VISIBLE);
        altitudeHelper.fetchAltitude(this, label -> {
            binding.progressAltitude.setVisibility(View.GONE);
            if (label != null && TextUtils.isEmpty(binding.etAltitude.getText())) {
                binding.etAltitude.setText(label);
            }
        });
    }

    // -----------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------

    private void onSaveClicked() {
        Integer sys = parseIntOrNull(binding.etSystolic.getText().toString());
        Integer dia = parseIntOrNull(binding.etDiastolic.getText().toString());
        Integer pulse = parseIntOrNull(binding.etPulse.getText().toString());

        if (sys == null || dia == null || pulse == null) {
            Toast.makeText(this, R.string.fix_fields_before_saving, Toast.LENGTH_LONG).show();
            return;
        }

        String arm = (String) binding.spinnerArm.getSelectedItem();
        String altitude = binding.etAltitude.getText().toString().trim();
        String note = binding.etNote.getText().toString().trim();

        Measurement measurement = new Measurement(
                selectedDateTime.getTimeInMillis(), sys, dia, pulse, arm,
                altitude.isEmpty() ? null : altitude,
                note.isEmpty() ? null : note);

        binding.btnSave.setEnabled(false);
        repository.insert(measurement, id -> {
            binding.btnSave.setEnabled(true);
            Toast.makeText(this, R.string.saved_confirmation, Toast.LENGTH_SHORT).show();
            // Deja el formulario en blanco de una vez -- antes solo se limpiaba la nota,
            // así que sistólica/diastólica/pulso/altitud de esta lectura ya guardada se
            // quedaban ahí, listos para que la siguiente entrada manual "heredara" esos
            // valores como si fueran predeterminados en vez de partir vacía.
            clearForm();
            fetchAltitudeBestEffort(); // altitud fresca para la siguiente lectura
            showScanningState(); // back to the live scanner, ready for the next reading
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
