#!/bin/bash
# Copies the app from the live site into www/, so the phone apps always match the web version.
set -e
SITE=https://offshoredaystracker.pages.dev
mkdir -p www/icons
for f in index.html uk12.json firebase-config.js manifest.webmanifest icons/icon.svg icons/icon-192.png icons/icon-512.png icons/apple-touch-icon.png; do
  curl -fsSL "$SITE/$f" -o "www/$f"
done
if ! grep -q "registerPlugin('ShipFence')" www/index.html; then
  echo "::error::The live site is older than v25. Upload the latest zip to Cloudflare Pages first, then run this again."
  exit 1
fi
echo "Got the app from $SITE"
