package com.frank.barcodehealthapp;

import android.content.Context;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Manages barcode scanning and product lookup
 * Includes camera initialization, barcode processing using ML Kit
 * and asynchronous API call to OpenFoodFacts for product details
 */
public class BarcodeManager {

    /**
     * Callback interface for delivering product lookup results
     */
    public interface ProductResultCallback {
        void onProductFound(String name, String brand, String ingredients);
        void onProductNotFound();
        void onError(String error);
    }

    /**
     * Starts the camera using CameraX and binds an analyzer to process frames
     * Sets up the camera preview and image analysis pipeline
     */
    public static void startCamera(Context context, PreviewView previewView, ExecutorService executor,
                                   ImageAnalysis.Analyzer analyzer) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Set up live camera preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Set up image analysis for barcode scanning
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(executor, analyzer);

                // Use the rear-facing camera
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // Apply camera configuration to lifecycle
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle((androidx.lifecycle.LifecycleOwner) context, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * Scans an image frame for barcodes using ML Kit
     * If a barcode is detected, passes the result to the callback
     */
    @OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    public static void scanBarcode(ImageProxy imageProxy, BarcodeScanCallback callback, int requiredStableFrames,
                                   String lastBarcode, List<String> scannedBarcodes) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        BarcodeScanning.getClient().process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String rawValue = barcode.getRawValue();
                        if (rawValue != null) {
                            callback.onBarcodeDetected(rawValue, requiredStableFrames, scannedBarcodes);
                        }
                    }
                })
                .addOnFailureListener(e -> Log.e("Scanner", "Detection failed", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * Callback interface to report barcode detection results from scanBarcode()
     */
    public interface BarcodeScanCallback {
        void onBarcodeDetected(String value, int requiredStableFrames, List<String> scannedBarcodes);
    }

    /**
     * Performs an async HTTP GET request to OpenFoodFacts api
     * to retrieve product data (name, brand, ingredients) by barcode
     */
    public static void lookupProductName(String barcode, ProductResultCallback callback) {
        new Thread(() -> {
            try {
                String finalBarcode = barcode; // make a new variable for the final barcode

                // Check for Walmart QR format
                if (barcode.contains("w-mt.co")) {
                    // Retrieve UPC code from walmart system
                    String upc = IngredientDataLoader.walmartQRLookup(barcode);

                    if (upc == null || upc.length() < 6) {
                        callback.onError("Could not extract UPC from Walmart QR code.");
                        return;
                    }
                    // Use found UPC as barcode
                    finalBarcode = upc;
                }

                // OpenFoodFacts UPC product ingredients lookup
                String urlString = "https://world.openfoodfacts.org/api/v2/product/" + finalBarcode;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) throw new Exception("HTTP error code: " + responseCode);

                // Reads data from adapted URL get request
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) result.append(line);
                reader.close();

                // Extracts desired product info (name/brand/ingredients)
                JSONObject json = new JSONObject(result.toString());
                if (json.getInt("status") == 1 && json.has("product")) {
                    JSONObject product = json.getJSONObject("product");
                    String name = product.optString("product_name", "Unnamed Product");
                    String brand = product.optString("brands", "Unknown Brand");
                    String ingredients = product.optString("ingredients_text", "No ingredients listed");
                    callback.onProductFound(name, brand, ingredients);
                } else {
                    callback.onProductNotFound();
                }
            } catch (Exception e) {
                e.printStackTrace();
                callback.onError("Error retrieving product info.");
            }
        }).start();
    }


}
