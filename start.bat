@echo off
rem ---------------------------------------------------------------------------
rem  Thin wrapper for start.ps1 - the real logic lives in the PowerShell script.
rem
rem  This file is deliberately pure ASCII: cmd.exe decodes .bat files using the
rem  active console code page, so non-ASCII text here would be garbled on
rem  machines that are not using a Chinese code page.
rem
rem    %~dp0  = directory of this .bat, always with a trailing backslash
rem    %*     = all arguments, quoting preserved (e.g. "start.bat 8080")
rem
rem  -NoProfile              : do not load the user profile, keeps startup predictable
rem  -ExecutionPolicy Bypass : runs even when the machine policy forbids scripts,
rem                            without changing the registry
rem ---------------------------------------------------------------------------
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
exit /b %ERRORLEVEL%
