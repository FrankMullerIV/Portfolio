package com.frank.barcodehealthapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.provider.MediaStore;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.view.PreviewView;
import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;

/**
 * Handles camera capture and OCR ingredient list extraction for offline use
 * Trims extracted text and fixes the "letters recognized as numbers" issue (o=0, 3=E, 8=B etc)
 */
public class OCRManager {

    public interface OCRCallback {
        void onIngredientsExtracted(@NonNull String ingredientText);
        void onFailure(Exception e);
    }

    private final Activity activity;
    private final OCRCallback callback;
    private final ActivityResultLauncher<Intent> cameraLauncher;

    private File photoFile;

    public OCRManager(@NonNull Activity activity,
                      @NonNull OCRCallback callback,
                      @NonNull PreviewView lifecycleOwner) {
        this.activity = activity;
        this.callback = callback;

        cameraLauncher = ((androidx.activity.ComponentActivity) activity)
                .registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() == Activity.RESULT_OK && photoFile != null && photoFile.exists()) {
                                Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                                runOCR(bitmap);
                                photoFile.delete();
                            } else {
                                callback.onFailure(new Exception("No photo captured"));
                            }
                        }
                );
    }

    /**
     * Launch the camera to take a photo for OCR
     */
    public void startOCRPrompt() {
        try {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            photoFile = File.createTempFile("ocr_photo_", ".jpg", dir);
            Uri photoUri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".provider", photoFile);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            e.printStackTrace();
            callback.onFailure(e);
            Toast.makeText(activity, "Failed to launch camera.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Run ML Kit OCR and extract ingredient section
     */
    private void runOCR(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener(result -> {
                    String fullText = result.getText();
                    String filtered = extractIngredientsSection(fullText);
                    if (filtered.isEmpty()) {
                        callback.onFailure(new Exception("Could not find ingredients section"));
                    } else {
                        String cleaned = numberLetterSwap(filtered);
                        callback.onIngredientsExtracted(cleaned);
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Extracts only the ingredient list from raw OCR text
     * Ignores text prior to reading the word "ingredient" which signals the start of "Ingredients:" list
     */
    private String extractIngredientsSection(String text) {
        String[] lines = text.split("\\r?\\n");
        StringBuilder ingredientsSection = new StringBuilder();
        boolean inIngredients = false;

        for (String line : lines) {
            String lower = line.toLowerCase().trim();

            // Start section if "ingredient" found
            if (!inIngredients && lower.contains("ingredient")) {
                inIngredients = true;
                int colon = line.indexOf(":");
                if (colon != -1 && colon < line.length() - 1) {
                    String afterColon = line.substring(colon + 1).trim();
                    if (!afterColon.isEmpty()) ingredientsSection.append(afterColon).append(" ");
                }
                continue;
            }

            if (inIngredients) {
                // End when finding words that commonly come after ingredient lists
                if (
                        lower.matches(".*(distributed|manufactured|guarantee|quality|price|walmart|value|product of|nutrition|percent|guaranteed|contact|phone|address|satisfaction).*")
                                || lower.isEmpty()
                ) {
                    break;
                }
                if (lower.equals(".") || lower.equals(")")) break;

                ingredientsSection.append(line.trim()).append(" ");
            }
        }
        // Returns only text from ingredients list (ideally)
        return ingredientsSection.toString().trim();
    }

    // Swaps of common OCR digit/letter errors (ocr mistakes o for 0, fixes that)
    private String numberLetterSwap(String text) {
        return text
                .replace('8', 'B')
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('5', 'S')
                .replace('6', 'G')
                .replace('4', 'A')
                .replace('2', 'Z')
                .replace('3', 'E');
    }

}
