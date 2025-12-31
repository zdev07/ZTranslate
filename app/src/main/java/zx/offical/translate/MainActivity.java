package zx.offical.translate;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ZTranslate";
    private ExecutorService cameraExecutor;
    private TextView translatedTextView;
    private PreviewView viewFinder;
    private TextRecognizer textRecognizer;
    private LanguageIdentifier languageIdentifier;
    private ActivityResultLauncher<String> galleryLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        translatedTextView = findViewById(R.id.translatedText);
        ImageButton galleryButton = findViewById(R.id.galleryButton);

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        languageIdentifier = LanguageIdentification.getClient();
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize Gallery Launcher
        galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    processGalleryImage(uri);
                }
            }
        );

        galleryButton.setOnClickListener(v -> galleryLauncher.launch("image/*"));

        if (allPermissionsGranted()) {
            viewFinder.post(this::startCamera);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }
    }

    private void processGalleryImage(Uri imageUri) {
        try {
            translatedTextView.setText("Processing gallery image...");
            InputImage image = InputImage.fromFilePath(this, imageUri);
            textRecognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String text = visionText.getText();
                    if (!text.isEmpty()) {
                        identifyAndTranslate(text);
                    } else {
                        translatedTextView.setText("No text found in image.");
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "OCR Failed", Toast.LENGTH_SHORT).show();
                });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder()
                        .setTargetRotation(viewFinder.getDisplay().getRotation())
                        .build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(viewFinder.getDisplay().getRotation())
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    @SuppressWarnings("UnsafeOptInUsageError")
                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage != null) {
                        InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                        textRecognizer.process(image)
                                .addOnSuccessListener(visionText -> {
                                    String text = visionText.getText();
                                    if (!text.isEmpty()) identifyAndTranslate(text);
                                })
                                .addOnCompleteListener(task -> imageProxy.close());
                    } else {
                        imageProxy.close();
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
                runOnUiThread(() -> translatedTextView.setText("Point at text or pick from gallery"));

            } catch (Exception e) {
                Log.e(TAG, "Camera failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void identifyAndTranslate(String text) {
        languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener(langCode -> {
                    if (!langCode.equals("und")) translateText(text, langCode);
                });
    }

    private void translateText(String text, String sourceLang) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build();
        final Translator translator = Translation.getClient(options);
        translator.downloadModelIfNeeded()
                .addOnSuccessListener(v -> {
                    translator.translate(text)
                            .addOnSuccessListener(result -> {
                                runOnUiThread(() -> translatedTextView.setText(result));
                            });
                });
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
