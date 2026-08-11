@echo off
setlocal

echo.
echo ======================================
echo   Scribit - publish to GitHub
echo ======================================
echo.

where git >nul 2>nul || (
  echo Git is not installed or not available in PATH.
  echo Install Git first, reopen CMD, then run this file again.
  pause
  exit /b 1
)

where gh >nul 2>nul || (
  echo GitHub CLI ^(gh^) is not installed or not available in PATH.
  echo Install GitHub CLI first, reopen CMD, then run this file again.
  pause
  exit /b 1
)

gh auth status >nul 2>nul || (
  echo You are not logged into GitHub CLI yet.
  echo Running: gh auth login
  gh auth login
  if errorlevel 1 exit /b 1
)

if not exist .git (
  git init
  git branch -M main
)

git add .
git diff --cached --quiet
if errorlevel 1 (
  git commit -m "Build Scribit Android app"
) else (
  echo No new local changes to commit.
)

gh repo view The-Chosen-One-O5/Scribit >nul 2>nul
if errorlevel 1 (
  echo Creating github.com/The-Chosen-One-O5/Scribit ...
  gh repo create The-Chosen-One-O5/Scribit --public --source=. --remote=origin --push
) else (
  echo Scribit repository already exists.
  git remote get-url origin >nul 2>nul || git remote add origin https://github.com/The-Chosen-One-O5/Scribit.git
  git push -u origin main
)

if errorlevel 1 (
  echo.
  echo Publish failed. Copy the error above and send it to ChatGPT.
  pause
  exit /b 1
)

echo.
echo Done. GitHub Actions will now build Scribit.apk and publish it in Releases.
echo Repo: https://github.com/The-Chosen-One-O5/Scribit
pause
