#!/bin/bash
set -euo pipefail

REPO_DIR="repo"
BRANCH="repo"

# Configure git globally so it applies to temp repos too
git config --global user.name "github-actions[bot]"
git config --global user.email "github-actions[bot]@users.noreply.github.com"

# Create a temporary directory for the repo branch
TEMP_DIR=$(mktemp -d)

# Check if repo branch exists
if git ls-remote --exit-code origin "$BRANCH" > /dev/null 2>&1; then
    git clone --branch "$BRANCH" --single-branch --depth 1 \
        "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "$TEMP_DIR"
else
    cd "$TEMP_DIR"
    git init
    git checkout -b "$BRANCH"
    git remote add origin "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
    cd -
fi

# Sync APKs and index to the temp directory
rsync -av --delete --exclude='.git' "$REPO_DIR/" "$TEMP_DIR/"

cd "$TEMP_DIR"

# Commit and push
git add -A
if git diff --cached --quiet; then
    echo "No changes to commit"
else
    git commit -m "Update extensions repo"
    git push origin "$BRANCH"
    echo "Pushed to $BRANCH"
fi

# Cleanup
rm -rf "$TEMP_DIR"
