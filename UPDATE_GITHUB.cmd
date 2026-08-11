@echo off
setlocal
set MSG=%*
if "%MSG%"=="" set MSG=Update Scribit

git add .
git diff --cached --quiet
if errorlevel 1 (
  git commit -m "%MSG%"
  git push
) else (
  echo Nothing changed. No commit created.
)
pause
