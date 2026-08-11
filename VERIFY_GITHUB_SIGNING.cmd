@echo off
setlocal

echo.
echo Scribit signing secrets currently configured on GitHub:
echo.
gh secret list -R The-Chosen-One-O5/Scribit

echo.
echo Required names:
echo   SCRIBIT_KEYSTORE_BASE64
echo   SCRIBIT_KEYSTORE_PASSWORD
echo   SCRIBIT_KEY_ALIAS
echo   SCRIBIT_KEY_PASSWORD
echo.
echo GitHub never prints the secret values here. That is expected.
pause
