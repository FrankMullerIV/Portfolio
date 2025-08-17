import json
# This reads upc_ingredients.json and ingredient_aliases.json
# If an item is in aliases but not upc, then it's added to the output file
# All files must be in the same directory
# Uses fuzzy matching functions from Ingredient Explorer to match app searching behavior

def levenshtein(s1, s2):
    # Standard Levenshtein distance algorithm
    if len(s1) < len(s2):
        return levenshtein(s2, s1)
    if len(s2) == 0:
        return len(s1)
    previous_row = list(range(len(s2) + 1))
    for i, c1 in enumerate(s1):
        current_row = [i + 1]
        for j, c2 in enumerate(s2):
            insertions = previous_row[j + 1] + 1
            deletions = current_row[j] + 1
            substitutions = previous_row[j] + (c1 != c2)
            current_row.append(min(insertions, deletions, substitutions))
        previous_row = current_row
    return previous_row[-1]

def get_closest_key(norm, keys):
    # 1. Substring match
    for key in keys:
        if key in norm or norm in key:
            return key
    # 2. Startswith/Endswith match
    for key in keys:
        if (key.startswith(norm) or key.endswith(norm) or
            norm.startswith(key) or norm.endswith(key)):
            return key
    # 3. Levenshtein distance ≤ 4
    minDist = float('inf')
    best = None
    for key in keys:
        dist = levenshtein(norm, key)
        if dist < minDist:
            minDist = dist
            best = key
    if minDist <= 4:
        return best
    return None

# Load ingredient_aliases.json
with open('ingredient_aliases.json', 'r', encoding='utf-8') as f:
    aliases = json.load(f)

# Load upc_ingredients.json
with open('upc_ingredients.json', 'r', encoding='utf-8') as f:
    upc_ingredients = json.load(f)

# Prepare set of lowercased ingredient names for fuzzy matching
upc_ingredient_names = set(
    entry['ingredient'].strip().lower() for entry in upc_ingredients if 'ingredient' in entry
)

missing_ingredients = []
for code, ingredient_name in aliases.items():
    ingredient_options = [name.strip().lower() for name in ingredient_name.split(',')]
    found = False
    for option in ingredient_options:
        # Fuzzy match with upc_ingredient_names
        if get_closest_key(option, upc_ingredient_names):
            found = True
            break
    if not found:
        missing_ingredients.append(f'{code}: {ingredient_name}')

with open('ingredient_checklist.txt', 'w', encoding='utf-8') as f:
    for entry in missing_ingredients:
        f.write(entry + '\n')

print(f"Done! {len(missing_ingredients)} missing ingredients written to ingredient_checklist.txt.")
