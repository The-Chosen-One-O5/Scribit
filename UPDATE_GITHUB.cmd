@echo off
setlocal
set MSG=%*
if "%MSG%"=="" set MSG=Update Scribit

REM Clean up the one-time v1.2 migration note if this project was updated by overlaying a patch.
if exist START_HERE_v1.2.txt del /q START_HERE_v1.2.txt
if exist START_HERE_v1.3.txt del /q START_HERE_v1.3.txt
if exist START_HERE_v1.3.1.txt del /q START_HERE_v1.3.1.txt

git add .
git diff --cached --quiet
if errorlevel 1 (
  git commit -m "%MSG%"
  git push
) else (
  echo Nothing changed. No commit created.
)
pause
