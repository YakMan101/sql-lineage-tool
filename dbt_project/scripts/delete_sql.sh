#!/bin/bash

INCLUDE_SEEDS=false
TARGET_DIR=""

for arg in "$@"; do
    case "$arg" in
        --include-seeds) INCLUDE_SEEDS=true ;;
        *) TARGET_DIR="$arg" ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

MODELS_PATH="$PROJECT_DIR/models"

if [ -n "$TARGET_DIR" ]; then
    MODELS_PATH="$PROJECT_DIR/$TARGET_DIR"
fi

# --include-seeds: check BQ existence for all seeds + SQL models, then drop
if [ "$INCLUDE_SEEDS" = true ]; then
    SEEDS_PATH="$PROJECT_DIR/seeds"
    MODELS_PATH_CHECK="${MODELS_PATH}"

    # Collect seed table names from CSV filenames
    SEED_TABLES=()
    while IFS= read -r f; do
        name="$(basename "$f" .csv)"
        SEED_TABLES+=("$name")
    done < <(find "$SEEDS_PATH" -name "*.csv" -type f 2>/dev/null)

    # Collect model table names from SQL filenames
    MODEL_TABLES=()
    while IFS= read -r f; do
        name="$(basename "$f" .sql)"
        MODEL_TABLES+=("$name")
    done < <(find "$MODELS_PATH_CHECK" -name "*.sql" -type f 2>/dev/null)

    if [ ${#SEED_TABLES[@]} -eq 0 ] && [ ${#MODEL_TABLES[@]} -eq 0 ]; then
        echo "[WARNING] No seed CSVs or SQL files found."
        exit 0
    fi

    # Build comma-separated lists for dbt macro args
    SEED_ARG="$(IFS=,; echo "${SEED_TABLES[*]}")"
    MODEL_ARG="$(IFS=,; echo "${MODEL_TABLES[*]}")"

    echo "Checking BQ tables..."
    echo ""
    dbt run-operation check_bq_tables \
        --args "{seed_tables: [${SEED_ARG}], model_tables: [${MODEL_ARG}]}" \
        --project-dir "$PROJECT_DIR" \
        --profiles-dir "$PROJECT_DIR"

    echo ""
    read -r -p "Drop all found BQ tables? (no local files will be changed) [y/N] " confirm
    if [[ ! "$confirm" =~ ^[Yy]$ ]]; then
        echo "Aborted."
        exit 0
    fi

    echo ""
    if ! dbt run-operation drop_seeds \
        --args "{seed_tables: [${SEED_ARG}], model_tables: [${MODEL_ARG}]}" \
        --project-dir "$PROJECT_DIR" \
        --profiles-dir "$PROJECT_DIR"; then
        echo "[WARNING] dbt drop_seeds failed — check your BQ connection and credentials"
    fi
    exit 0
fi

# Default: delete local SQL files
if [ ! -d "$MODELS_PATH" ]; then
    echo "[WARNING] Directory not found: $MODELS_PATH"
    exit 1
fi

SQL_FILES=()
while IFS= read -r f; do SQL_FILES+=("$f"); done < <(find "$MODELS_PATH" -name "*.sql" -type f)

if [ ${#SQL_FILES[@]} -eq 0 ]; then
    echo "[WARNING] No SQL files found in $MODELS_PATH"
    exit 0
fi

echo "Files to delete:"
for f in "${SQL_FILES[@]}"; do echo "  [SQL]  $f"; done
echo ""

read -r -p "Are you sure? [y/N] " confirm
if [[ ! "$confirm" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
fi

echo ""

deleted=0
for f in "${SQL_FILES[@]}"; do
    if [ -f "$f" ]; then
        rm "$f"
        echo "[INFO] Deleted: $f"
        ((deleted++))
    else
        echo "[WARNING] File not found, skipping: $f"
    fi
done

echo ""
echo "Done. $deleted SQL file(s) deleted."
