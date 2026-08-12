@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "MSG=%*"
if "%MSG%"=="" set "MSG=Scribit v1.4.2 source-sync repair"
set "APP_FILE=app\src\main\java\com\thechosenone\scribit\ui\ScribitApp.kt"

echo.
echo ===============================================
echo   Scribit - verified GitHub update
echo ===============================================
echo.

where git >nul 2>nul || (
  echo ERROR: Git is not available in PATH.
  pause
  exit /b 1
)

if not exist .git (
  echo ERROR: This is not your Scribit Git repository folder.
  echo Extract this repair ZIP directly into the existing Scribit repo, then run this file again.
  pause
  exit /b 1
)

REM Never continue a half-finished rebase/merge from an earlier attempt.
if exist .git\rebase-merge (
  echo ERROR: A Git rebase is already in progress.
  echo Finish or abort that rebase before running this updater.
  pause
  exit /b 1
)
if exist .git\rebase-apply (
  echo ERROR: A Git rebase is already in progress.
  echo Finish or abort that rebase before running this updater.
  pause
  exit /b 1
)
if exist .git\MERGE_HEAD (
  echo ERROR: A Git merge is already in progress.
  echo Finish or abort that merge before running this updater.
  pause
  exit /b 1
)

if not exist "%APP_FILE%" (
  echo ERROR: %APP_FILE% is missing.
  pause
  exit /b 1
)

REM Critical feature check BEFORE Git does anything.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[IO.File]::ReadAllText('%APP_FILE%'); if(-not ($s.Contains('Add More') -and $s.Contains('Manage categories'))) { Write-Host 'ERROR: New category UI is missing from local ScribitApp.kt.' -ForegroundColor Red; exit 1 }"
if errorlevel 1 (
  echo The repair source was not actually overlaid into this repo.
  echo Re-extract Scribit-v1.4.2-SOURCE-SYNC-patch.zip into this exact folder and choose Replace All.
  pause
  exit /b 1
)

echo [1/7] Updating local Git history first...
git pull --rebase --autostash origin main
if errorlevel 1 (
  echo.
  echo ERROR: Git could not synchronize with origin/main.
  echo Do NOT force-push. Send the error above to ChatGPT.
  pause
  exit /b 1
)

REM Re-check because autostash must have restored the feature source.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[IO.File]::ReadAllText('%APP_FILE%'); if(-not ($s.Contains('Add More') -and $s.Contains('Manage categories'))) { exit 1 }"
if errorlevel 1 (
  echo ERROR: The feature source disappeared while synchronizing Git history.
  echo Nothing will be pushed.
  pause
  exit /b 1
)

echo [2/7] Verifying release source snapshot...
where certutil >nul 2>nul || (
  echo ERROR: Windows certutil is unavailable.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "release\verify-source.ps1"
if errorlevel 1 (
  echo ERROR: Local source does not match release\source-manifest.sha256.
  echo Nothing will be pushed.
  pause
  exit /b 1
)

echo [3/7] Staging ALL project changes...
git add -A
git diff --cached --quiet
if not errorlevel 1 (
  echo Nothing changed. No commit created.
  pause
  exit /b 0
)

echo.
echo Files being committed:
git diff --cached --name-status
echo.

echo [4/7] Creating commit...
git commit -m "%MSG%"
if errorlevel 1 (
  echo ERROR: Commit failed.
  pause
  exit /b 1
)

echo [5/7] Pushing to GitHub...
git push origin main
if errorlevel 1 (
  echo Remote changed while we were working. Rebasing once and retrying...
  git pull --rebase origin main
  if errorlevel 1 (
    echo ERROR: Rebase failed. Do NOT force-push.
    pause
    exit /b 1
  )
  git push origin main
  if errorlevel 1 (
    echo ERROR: Push failed again. Do NOT force-push.
    pause
    exit /b 1
  )
)

echo [6/7] PROVING GitHub has the same app source...
git fetch origin main >nul
for /f %%H in ('git rev-parse HEAD') do set "LOCAL_HEAD=%%H"
for /f %%H in ('git rev-parse origin/main') do set "REMOTE_HEAD=%%H"
for /f %%H in ('git rev-parse HEAD:app/src') do set "LOCAL_APP_TREE=%%H"
for /f %%H in ('git rev-parse origin/main:app/src') do set "REMOTE_APP_TREE=%%H"

if /I not "!LOCAL_HEAD!"=="!REMOTE_HEAD!" (
  echo ERROR: GitHub main is not the commit that was just pushed.
  echo Local : !LOCAL_HEAD!
  echo Remote: !REMOTE_HEAD!
  pause
  exit /b 1
)
if /I not "!LOCAL_APP_TREE!"=="!REMOTE_APP_TREE!" (
  echo ERROR: app/src on GitHub does NOT match local app/src.
  echo Local app tree : !LOCAL_APP_TREE!
  echo Remote app tree: !REMOTE_APP_TREE!
  echo Do not install the APK. Send this output to ChatGPT.
  pause
  exit /b 1
)

REM Verify the remote file itself contains both UI markers.
git show origin/main:%APP_FILE:\=/% > "%TEMP%\scribit_remote_app.txt"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[IO.File]::ReadAllText($env:TEMP+'\scribit_remote_app.txt'); if(-not ($s.Contains('Add More') -and $s.Contains('Manage categories'))) { exit 1 }"
del /q "%TEMP%\scribit_remote_app.txt" >nul 2>nul
if errorlevel 1 (
  echo ERROR: GitHub's ScribitApp.kt still does not contain the new category UI.
  echo Do not install the APK.
  pause
  exit /b 1
)

echo [7/7] GitHub source verified.
echo.
echo SUCCESS: local source and GitHub main are identical.
echo Verified remotely: Add More + Manage categories.
echo GitHub Actions can now build the actual source you intended to publish.
echo.

where gh >nul 2>nul
if not errorlevel 1 (
  echo Waiting a few seconds for the workflow run to appear...
  timeout /t 6 /nobreak >nul
  for /f %%R in ('gh run list --workflow build-apk.yml --branch main --limit 1 --json databaseId --jq ".[0].databaseId" 2^>nul') do set "RUN_ID=%%R"
  if defined RUN_ID (
    echo Watching GitHub Actions run !RUN_ID! ...
    gh run watch !RUN_ID! --exit-status
    if errorlevel 1 (
      echo.
      echo GitHub source is correct, but the Android build failed.
      echo Open Actions and send the failing build error to ChatGPT.
      pause
      exit /b 1
    )
  )
)

echo.
echo Done.
pause
