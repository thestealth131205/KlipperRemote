#!/bin/bash
# ============================================================
#  push-and-deploy.sh – KlipperRemote
#  1. Commit & push all changes to GitHub
#  2. GitHub Actions builds a *signed* release APK
#  3. The APK is published to GitHub Releases
#
#  No secrets are stored in this script. Authentication is
#  handled entirely by the GitHub CLI (`gh auth login`) or the
#  GH_TOKEN / GITHUB_TOKEN environment variable. The signing
#  keystore lives only in GitHub Actions secrets.
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
log()     { echo -e "${BLUE}[KLIPPER]${NC} $1"; }
success() { echo -e "${GREEN}[  OK  ]${NC} $1"; }
warn()    { echo -e "${YELLOW}[ WARN ]${NC} $1"; }
error()   { echo -e "${RED}[ERROR ]${NC} $1"; exit 1; }
title()   { echo -e "\n${BOLD}${GREEN}══════════════════════════════════════${NC}"; \
            echo -e "${BOLD}${GREEN}  $1${NC}"; \
            echo -e "${BOLD}${GREEN}══════════════════════════════════════${NC}\n"; }

# ── Voraussetzungen prüfen ────────────────────────────────
command -v git >/dev/null 2>&1 || error "git ist nicht installiert."
command -v gh  >/dev/null 2>&1 || error "GitHub CLI (gh) ist nicht installiert. Siehe https://cli.github.com"

GRADLE_FILE="${SCRIPT_DIR}/app/build.gradle.kts"
[ -f "$GRADLE_FILE" ] || error "app/build.gradle.kts nicht gefunden."

VERSION=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)
VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "main")

title "KLIPPERREMOTE PUSH & RELEASE"
log "Version:  ${VERSION} (Build ${VERSION_CODE})"
log "Branch:   ${BRANCH}"

# ── Commit-Nachricht ──────────────────────────────────────
COMMIT_MSG="${1:-Release v${VERSION} ($(date '+%d.%m.%Y %H:%M'))}"

# ── Änderungen committen ──────────────────────────────────
if [ -n "$(git status --porcelain)" ]; then
    log "Übertrage Änderungen..."
    git add -A
    git commit -m "$COMMIT_MSG"
    success "Commit erstellt: ${COMMIT_MSG}"
else
    warn "Keine Änderungen zu committen – pushe bestehenden Stand."
fi

log "Pushe nach origin/${BRANCH}..."
git push origin "$BRANCH"
success "Push abgeschlossen."

# ── Auf GitHub-Actions-Build warten ───────────────────────
title "GITHUB ACTIONS BUILD"
log "Warte auf Start des Build-Workflows..."
sleep 8

RUN_ID=$(gh run list --branch "$BRANCH" --workflow "build.yml" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || echo "")
if [ -z "$RUN_ID" ]; then
    warn "Konnte Run-ID nicht ermitteln. Prüfe den Status manuell:"
    warn "  gh run list --workflow build.yml"
    exit 0
fi

log "Build-Run: ${RUN_ID} – verfolge Status (Strg+C bricht nur die Anzeige ab)..."
if gh run watch "$RUN_ID" --exit-status 2>/dev/null; then
    success "Build erfolgreich!"
else
    error "Build fehlgeschlagen → $(gh run view "$RUN_ID" --json url --jq '.url' 2>/dev/null)"
fi

# ── Release ermitteln ─────────────────────────────────────
title "RELEASE"
sleep 3
LATEST_TAG=$(gh release list --limit 1 --json tagName --jq '.[0].tagName' 2>/dev/null || echo "")
if [ -n "$LATEST_TAG" ]; then
    REPO=$(gh repo view --json nameWithOwner --jq '.nameWithOwner' 2>/dev/null)
    success "Release veröffentlicht: ${LATEST_TAG}"
    echo -e "  🔗 https://github.com/${REPO}/releases/tag/${LATEST_TAG}"
else
    warn "Kein Release gefunden – prüfe die Actions-Logs."
fi

title "✅ FERTIG"
echo -e "  Version:  ${VERSION} (Build ${VERSION_CODE})"
echo -e "  APK:      KlipperRemote-${VERSION}.apk (signiert)"
