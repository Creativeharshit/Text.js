#!/data/data/com.termux/files/usr/bin/bash
# Usage: GH_TOKEN=ghp_xxx ./push-to-github.sh username/repo
set -e
[ -z "$GH_TOKEN" ] || [ -z "$1" ] && { echo "Use: GH_TOKEN=token ./push-to-github.sh user/repo"; exit 1; }
cd "$(dirname "$0")"
[ -d .git ] || git init -b main
git add -A
git commit -m "TermiCast update" || true
git remote remove origin 2>/dev/null || true
git remote add origin "https://$GH_TOKEN@github.com/$1.git"
git push -u origin main --force
git remote set-url origin "https://github.com/$1.git"   # token URL se hata diya
echo "Pushed. GitHub > Actions me APK build dekho."
