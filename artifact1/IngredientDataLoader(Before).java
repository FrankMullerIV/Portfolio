package com.frank.barcodehealthapp;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles all data loading and normalization logic related to ingredients
 * This includes aliases, classifications, purposes, and formatted display rendering
 */
public class IngredientDataLoader {

    // Stores mappings of alias > canonical ingredient names
    private static final Map<String, String> aliasMap = new HashMap<>();

    /**
     * Loads alias map from assets if it hasn't been initialized.
     */
    public static void setAliasMap(Context context) {
        if (aliasMap.isEmpty()) {
            aliasMap.putAll(loadAliasMap(context));
        }
    }

    /**
     * Normalizes an ingredient name into a standardized format for comparison
     * - Lowercases, strips non-alphanumeric characters
     * - Substitutes known aliases
     * - Handles vitamin naming (B12 > vitaminb12)
     * - Handles plural-to-singular fallback
     */
    public static String normalizeName(String name, Context context) {
        setAliasMap(context);
        if (name == null) return "";
        String base = name.toLowerCase().replaceAll("[^a-z0-9]", "");

        String alias = aliasMap.get(base);
        if (alias != null) return alias.replaceAll("[^a-z0-9]", "");

        // Try singular fallback
        if (base.endsWith("s")) {
            alias = aliasMap.get(base.substring(0, base.length() - 1));
            if (alias != null) return alias.replaceAll("[^a-z0-9]", "");
        }

        // Vitamin fallbacks
        if (base.matches("^b\\d{1,2}$")) {
            alias = aliasMap.get("vitamin" + base);
            if (alias != null) return alias.replaceAll("[^a-z0-9]", "");
        }

        if (base.matches("^vitb\\d{1,2}$")) {
            alias = aliasMap.get("vitamin" + base.substring(3));
            if (alias != null) return alias.replaceAll("[^a-z0-9]", "");
        }

        if (base.matches("^vitaminb\\d{1,2}$")) return base;

        return base;
    }

    /**
     * Loads the ingredient_purposes.json asset and builds:
     * - A map of normalized ingredient > [types]
     * - A map of type > purpose description
     */
    public static Map<String, List<String>> loadIngredientPurposeMap(Context context, Map<String, String> typePurposeMap) {
        setAliasMap(context);
        Map<String, List<String>> map = new HashMap<>();

        try (InputStream is = context.getAssets().open("ingredient_purposes.json")) {
            String jsonStr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    : null;

            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String type = item.getString("Type").trim();
                String purpose = item.optString("Purpose", "").trim();

                typePurposeMap.put(type.toLowerCase(), purpose);

                // Map each ingredient to this type
                JSONArray ingredients = item.getJSONArray("Ingredients");
                for (int j = 0; j < ingredients.length(); j++) {
                    String normalized = normalizeName(ingredients.getString(j), context);
                    map.computeIfAbsent(normalized, k -> new ArrayList<>()).add(type);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return map;
    }

    /**
     * Loads raw alias mappings from ingredient_aliases.json asset
     * Converts both key and value to lowercase and strips punctuation for consistency
     */
    public static Map<String, String> loadIngredientAliases(Context context) {
        Map<String, String> aliasMap = new HashMap<>();
        try {
            InputStream is = context.getAssets().open("ingredient_aliases.json");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();

            JSONObject obj = new JSONObject(new String(buffer, StandardCharsets.UTF_8));
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String alias = keys.next();
                aliasMap.put(
                        alias.toLowerCase().replaceAll("[^a-z0-9]", ""),
                        obj.getString(alias).toLowerCase().replaceAll("[^a-z0-9]", "")
                );
            }
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
        return aliasMap;
    }

    /**
     * Loads ingredient classifications and raw descriptions from upc_ingredients.json
     * Populates both a classification map and a map of full JSON objects
     */
    public static Map<String, String> loadIngredientClassifications(Context context, Map<String, JSONObject> jsonObjects) {
        setAliasMap(context);
        Map<String, String> map = new HashMap<>();

        try (InputStream is = context.getAssets().open("upc_ingredients.json")) {
            String jsonStr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    : null;

            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String normalized = normalizeName(item.getString("ingredient"), context);
                String classification = item.getString("classification");

                map.put(normalized, classification);
                jsonObjects.put(normalized, item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return map;
    }

    /**
     * Loads alias mappings using newer JSON parser logic
     * This is the core loader used internally by setAliasMap()
     */
    public static Map<String, String> loadAliasMap(Context context) {
        Map<String, String> aliasMap = new HashMap<>();
        try (InputStream is = context.getAssets().open("ingredient_aliases.json")) {
            String jsonStr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    : null;

            JSONObject obj = new JSONObject(jsonStr);
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String key = it.next();
                String value = obj.getString(key);
                aliasMap.put(
                        key.toLowerCase().replaceAll("[^a-z0-9]", ""),
                        value.toLowerCase().replaceAll("[^a-z0-9]", "")
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return aliasMap;
    }

    /**
     * Cleans and splits an ingredient list into a unique list of ingredients
     * - Removes redundant spaces and trailing punctuation
     * - Attempts to skip prefaces like "Advice:"
     * - Flattens one level of parenthetical ingredients
     */
    public static List<String> extractFlattenedIngredients(String ingredientsText) {
        List<String> list = new ArrayList<>();
        if (ingredientsText == null) return list;

        String cleanText = ingredientsText.trim().replaceAll("\\.+$", "").replaceAll(" +", " ");

        // Remove common preambles like "Advice: ..." if found
        int idx = cleanText.toLowerCase().lastIndexOf("advice");
        if (idx != -1) {
            cleanText = cleanText.substring(idx + "advice".length()).replaceFirst("[:.\\s]*", "");
        } else {
            int lastPeriod = cleanText.lastIndexOf('.');
            if (lastPeriod != -1 && lastPeriod < cleanText.length() - 1) {
                String afterPeriod = cleanText.substring(lastPeriod + 1).trim();
                if (afterPeriod.contains(",")) cleanText = afterPeriod;
            }
        }

        // Split by commas and flatten single parentheses
        String[] rawParts = cleanText.split(",");
        Set<String> seen = new LinkedHashSet<>();
        for (String part : rawParts) {
            String trimmed = part.trim().replaceAll("(?i)^and\\s+", "");
            if (trimmed.isEmpty()) continue;

            int open = trimmed.indexOf('(');
            int close = trimmed.lastIndexOf(')');
            if (open >= 0 && close == trimmed.length() - 1) {
                String main = trimmed.substring(0, open).trim().replaceAll("(?i)^and\\s+", "");
                if (!main.isEmpty()) seen.add(main);
                String inside = trimmed.substring(open + 1, close);
                for (String sub : inside.split(",")) {
                    String s = sub.trim().replaceAll("(?i)^and\\s+", "");
                    if (!s.isEmpty()) seen.add(s);
                }
            } else {
                seen.add(trimmed);
            }
        }

        return new ArrayList<>(seen);
    }

    /**
     * Attempts to find the closest key match for a given input string using:
     * - Substring or prefix/suffix containment
     * - Levenshtein distance ≤ 4
     */
    public static String getClosestKey(String norm, Set<String> keys) {
        for (String key : keys) {
            if (key.contains(norm) || norm.contains(key)) return key;
        }
        for (String key : keys) {
            if (key.startsWith(norm) || key.endsWith(norm) || norm.startsWith(key) || norm.endsWith(key)) return key;
        }

        int minDist = Integer.MAX_VALUE;
        String best = null;
        for (String key : keys) {
            int dist = levenshtein(norm, key);
            if (dist < minDist) {
                minDist = dist;
                best = key;
            }
        }

        return minDist <= 4 ? best : null;
    }

    /**
     * Computes the Levenshtein distance between two strings
     * Used to determine "closeness" for fuzzy fallback matching
     */
    public static int levenshtein(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int j = 0; j < costs.length; j++) costs[j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= s2.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        s1.charAt(i - 1) == s2.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }

        return costs[s2.length()];
    }

    public static int levenshteinDistance(String a, String b) {
        return levenshtein(a, b);
    }

    /**
     * Attempts to retrieve a human readable description for the given ingredient
     * Tries exact match, variations, fuzzy matching, and closest match
     */
    public static String getIngredientDescription(String ingredient, Map<String, JSONObject> jsonObjects, Context context) {
        String norm = normalizeName(ingredient, context);
        JSONObject obj = jsonObjects.get(norm);
        if (obj != null) return obj.optString("description", null);

        // Handle vitamin shorthand fallback
        if (norm.matches("^(vitb|vitaminb)[0-9]{1,2}$")) {
            obj = jsonObjects.get("vitaminb" + norm.replaceAll("[^0-9]", ""));
            if (obj != null) return obj.optString("description", null);
        }
        if (norm.matches("^b[0-9]{1,2}$")) {
            obj = jsonObjects.get("vitamin" + norm);
            if (obj != null) return obj.optString("description", null);
        }

        String closest = getClosestKey(norm, jsonObjects.keySet());
        if (closest != null) {
            obj = jsonObjects.get(closest);
            if (obj != null) return obj.optString("description", null);
        }

        // Absolute fallback: find closest Levenshtein match
        int minDist = Integer.MAX_VALUE;
        String absoluteClosest = null;
        for (String key : jsonObjects.keySet()) {
            int dist = levenshtein(norm, key);
            if (dist < minDist) {
                minDist = dist;
                absoluteClosest = key;
            }
        }
        if (absoluteClosest != null) {
            obj = jsonObjects.get(absoluteClosest);
            if (obj != null) return obj.optString("description", null);
        }

        return null;
    }

    /**
     * Returns a color corresponding to the ingredient’s classification.
     * Flagged ingredients override this and are always shown in red.
     */
    public static int getColorForIngredient(String ingredient, Map<String, String> classificationMap, Context context) {
        String normalized = normalizeName(ingredient, context);

        if (FlaggedIngredientManager.isFlagged(context, normalized)) {
            return Color.RED;
        }

        String classification = classificationMap.get(normalized);

        // Fallback: try substring matches if exact classification is missing
        if (classification == null) {
            for (Map.Entry<String, String> entry : classificationMap.entrySet()) {
                if (normalized.contains(entry.getKey()) || entry.getKey().contains(normalized)) {
                    classification = entry.getValue();
                    break;
                }
            }
        }

        // Final fallback using closest match
        if (classification == null && !classificationMap.isEmpty()) {
            int minDist = Integer.MAX_VALUE;
            String absoluteClosest = null;
            for (String key : classificationMap.keySet()) {
                int dist = levenshtein(normalized, key);
                if (dist < minDist) {
                    minDist = dist;
                    absoluteClosest = key;
                }
            }
            if (absoluteClosest != null) {
                classification = classificationMap.get(absoluteClosest);
            }
        }

        if (classification == null) return Color.DKGRAY;

        switch (classification.trim().toLowerCase()) {
            case "natural": return Color.rgb(0, 128, 0);
            case "artificial": return Color.rgb(255, 140, 0);
            case "both": return Color.MAGENTA;
            default: return Color.GRAY;
        }
    }

    /**
     * Builds a styled SpannableStringBuilder that includes ingredient name, types,
     * and colored clickable spans for use in the product info display.
     */
    public static SpannableStringBuilder formatIngredientsText(Context context,
                                                               String name, String brand, String ingredientsText,
                                                               boolean isDetailedMode, TextView productInfoText,
                                                               Map<String, List<String>> ingredientPurposeMap,
                                                               Map<String, String> typePurposeMap,
                                                               Map<String, JSONObject> ingredientJsonObjects,
                                                               Map<String, String> ingredientClassificationMap) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(name).append(" (").append(brand).append(")\n\n");

        List<String> ingredients = extractFlattenedIngredients(ingredientsText);

        for (int i = 0; i < ingredients.size(); i++) {
            String raw = ingredients.get(i);
            String key = normalizeName(raw, context);
            List<String> types = ingredientPurposeMap.getOrDefault(key, new ArrayList<>());
            boolean shouldTruncate = types.size() > 3;
            List<String> shown = types.subList(0, Math.min(3, types.size()));

            String display = isDetailedMode && !types.isEmpty()
                    ? raw + ": " + String.join(", ", shown) + (shouldTruncate ? ", ..." : "")
                    : raw;

            int start = builder.length();
            builder.append(display);

            InterfaceManager.applyColorSpan(context, builder, raw, start, display, productInfoText,
                    ingredientJsonObjects, ingredientClassificationMap,
                    name, brand, ingredientsText,
                    isDetailedMode, ingredientPurposeMap, typePurposeMap, ingredientClassificationMap);

            if (isDetailedMode && !types.isEmpty()) {
                InterfaceManager.applyClickableTypeSpans(context, builder, types, start, typePurposeMap);
            }

            // If cropped out, make "..." clickable to expand
            if (shouldTruncate) {
                int ellipsisStart = builder.toString().indexOf("...", start);
                if (ellipsisStart != -1) {
                    int finalI = i;
                    builder.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(@NonNull View widget) {
                            SpannableStringBuilder expanded = InterfaceManager.buildExpandedDisplay(
                                    context, name, brand, ingredients, finalI, ingredientsText, isDetailedMode,
                                    productInfoText, ingredientPurposeMap, typePurposeMap, ingredientJsonObjects, ingredientClassificationMap
                            );
                            InterfaceManager.updateProductDisplay(productInfoText, expanded);
                        }
                    }, ellipsisStart, ellipsisStart + 3, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            builder.append(isDetailedMode ? "\n" : (i < ingredients.size() - 1 ? ", " : ""));
        }

        return builder;
    }

    /**
     * Exposes the internal alias map so its initialized before use.
     */
    public static Map<String, String> getAliasMap(Context context) {
        setAliasMap(context);
        return aliasMap;
    }
}
